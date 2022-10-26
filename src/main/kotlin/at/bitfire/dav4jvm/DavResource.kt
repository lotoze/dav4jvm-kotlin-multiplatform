/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package at.bitfire.dav4jvm

import at.bitfire.dav4jvm.XmlUtils.insertTag
import at.bitfire.dav4jvm.XmlUtils.propertyName
import at.bitfire.dav4jvm.exception.ConflictException
import at.bitfire.dav4jvm.exception.DavException
import at.bitfire.dav4jvm.exception.ForbiddenException
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.exception.NotFoundException
import at.bitfire.dav4jvm.exception.PreconditionFailedException
import at.bitfire.dav4jvm.exception.ServiceUnavailableException
import at.bitfire.dav4jvm.exception.UnauthorizedException
import at.bitfire.dav4jvm.property.SyncToken
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.EOFException
import java.io.IOException
import java.io.Reader
import java.io.StringWriter
import java.net.HttpURLConnection
import java.util.logging.Level
import java.util.logging.Logger
import at.bitfire.dav4jvm.Response as DavResponse

/**
 * Represents a WebDAV resource at the given location and allows WebDAV
 * requests to be performed on this resource.
 *
 * Requests are executed synchronously (blocking). If no error occurs, the given
 * callback will be called. Otherwise, an exception is thrown. *These callbacks
 * don't need to close the response.*
 *
 * To cancel a request, interrupt the thread. This will cause the requests to
 * throw `InterruptedException` or `InterruptedIOException`.
 *
 * @param httpClient    [OkHttpClient] to access this object (must not follow redirects)
 * @param location      location of the WebDAV resource
 * @param log           will be used for logging
 */
open class DavResource @JvmOverloads constructor(
        val httpClient: OkHttpClient,
        location: HttpUrl,
        val log: Logger = Dav4jvm.log
) {

    companion object {
        const val MAX_REDIRECTS = 5

        const val HTTP_MULTISTATUS = 207
        val MIME_XML = "application/xml; charset=utf-8".toMediaType()

        val PROPFIND = Property.Name(XmlUtils.NS_WEBDAV, "propfind")
        val PROPERTYUPDATE = Property.Name(XmlUtils.NS_WEBDAV, "propertyupdate")
        val SET = Property.Name(XmlUtils.NS_WEBDAV, "set")
        val REMOVE = Property.Name(XmlUtils.NS_WEBDAV, "remove")
        val PROP = Property.Name(XmlUtils.NS_WEBDAV, "prop")
        val HREF = Property.Name(XmlUtils.NS_WEBDAV, "href")

        val XML_SIGNATURE = "<?xml".toByteArray()
    }

    /**
     * URL of this resource (changes when being redirected by server)
     */
    var location: HttpUrl
        private set             // allow internal modification only (for redirects)

    init {
        // Don't follow redirects (only useful for GET/POST).
        // This means we have to handle 30x responses ourselves.
        require(!httpClient.followRedirects) { "httpClient must not follow redirects automatically" }

        this.location = location
    }

    override fun toString() = location.toString()


    /**
     * Gets the file name of this resource. See [HttpUtils.fileName] for details.
     */
    fun fileName() = HttpUtils.fileName(location)


    /**
     * Sends an OPTIONS request to this resource without HTTP compression (because some servers have
     * broken compression for OPTIONS). Doesn't follow redirects.
     *
     * @param callback called with server response unless an exception is thrown
     *
     * @throws IOException on I/O error
     * @throws HttpException on HTTP error
     * @throws DavException on HTTPS -> HTTP redirect
     */
    @Throws(IOException::class, HttpException::class)
    fun options(callback: (davCapabilities: Set<String>, response: Response) -> Unit) {
        httpClient.newCall(Request.Builder()
                .method("OPTIONS", null)
                .header("Content-Length", "0")
                .url(location)
                .header("Accept-Encoding", "identity")      // disable compression
                .build()).execute().use { response ->
            checkStatus(response)
            callback(HttpUtils.listHeader(response, "DAV").map { it.trim() }.toSet(), response)
        }
    }

    /**
     * Sends a MOVE request to this resource. Follows up to [MAX_REDIRECTS] redirects.
     * Updates [location] on success.
     *
     * @param destination where the resource shall be moved to
     * @param forceOverride whether resources are overwritten when they already exist in destination
     *
     * @throws IOException on I/O error
     * @throws HttpException on HTTP error
     * @throws DavException on WebDAV error or HTTPS -> HTTP redirect
     */
    @Throws(IOException::class, HttpException::class, DavException::class)
    fun move(destination: HttpUrl, forceOverride: Boolean, callback: (response: Response) -> Unit) {
        val requestBuilder = Request.Builder()
                .method("MOVE", null)
                .header("Content-Length", "0")
                .header("Destination", destination.toString())

        if (forceOverride) requestBuilder.header("Overwrite", "F")

        followRedirects {
            requestBuilder.url(location)
            httpClient.newCall(requestBuilder
                    .build())
                    .execute()
        }.use { response ->
            checkStatus(response)
            if (response.code == HTTP_MULTISTATUS)
                /* Multiple resources were to be affected by the MOVE, but errors on some
                of them prevented the operation from taking place.
                [_] (RFC 4918 9.9.4. Status Codes for MOVE Method) */
                throw HttpException(response)

            // update location
            location.resolve(response.header("Location") ?: destination.toString())?.let {
                location = it
            }

            callback(response)
        }
    }

    /**
     * Sends a COPY request for this resource. Follows up to [MAX_REDIRECTS] redirects.
     *
     * @param destination where the resource shall be copied to
     * @param forceOverride whether resources are overwritten when they already exist in destination
     *
     * @throws IOException on I/O error
     * @throws HttpException on HTTP error
     * @throws DavException on WebDAV error or HTTPS -> HTTP redirect
     */
    @Throws(IOException::class, HttpException::class, DavException::class)
    fun copy(destination:HttpUrl, forceOverride: Boolean, callback: (response: Response) -> Unit) {
        val requestBuilder = Request.Builder()
                .method("COPY", null)
                .header("Content-Length", "0")
                .header("Destination", destination.toString())

        if (forceOverride) requestBuilder.header("Overwrite", "F")

        followRedirects {
            requestBuilder.url(location)
            httpClient.newCall(requestBuilder
                    .build())
                    .execute()
        }.use{ response ->
            checkStatus(response)

            if (response.code == HTTP_MULTISTATUS)
                /* Multiple resources were to be affected by the COPY, but errors on some
                of them prevented the operation from taking place.
                [_] (RFC 4918 9.8.5. Status Codes for COPY Method) */
                throw HttpException(response)

            callback(response)
        }
    }

    /**
     * Sends a MKCOL request to this resource. Follows up to [MAX_REDIRECTS] redirects.
     *
     * @throws IOException on I/O error
     * @throws HttpException on HTTP error
     * @throws DavException on HTTPS -> HTTP redirect
     */
    @Throws(IOException::class, HttpException::class)
    fun mkCol(xmlBody: String?, callback: (response: Response) -> Unit) {
        val rqBody = xmlBody?.toRequestBody(MIME_XML)

        followRedirects {
            httpClient.newCall(Request.Builder()
                    .method("MKCOL", rqBody)
                    .url(location)
                    .build()).execute()
        }.use { response ->
            checkStatus(response)
            callback(response)
        }
    }

    /**
     * Sends a HEAD request to the resource.
     *
     * Follows up to [MAX_REDIRECTS] redirects.
     *
     * @param callback called with server response unless an exception is thrown
     *
     * @throws IOException on I/O error
     * @throws HttpException on HTTP error
     * @throws DavException on HTTPS -> HTTP redirect
     */
    fun head(callback: (response: Response) -> Unit) {
        followRedirects {
            httpClient.newCall(
                Request.Builder()
                    .head()
                    .url(location)
                    .build()
            ).execute()
        }.use { response ->
            checkStatus(response)
            callback(response)
        }
    }

    /**
     * Sends a GET request to the resource. Sends `Accept-Encoding: identity` to disable
     * compression, because compression might change the ETag.
     *
     * Follows up to [MAX_REDIRECTS] redirects.
     *
     * @param accept   value of `Accept` header (always sent for clarity; use *&#47;* if you don't care)
     * @param callback called with server response unless an exception is thrown
     *
     * @throws IOException on I/O error
     * @throws HttpException on HTTP error
     * @throws DavException on HTTPS -> HTTP redirect
     */
    @Deprecated("Use get(accept, headers, callback) with explicit Accept-Encoding instead")
    @Throws(IOException::class, HttpException::class)
    fun get(accept: String, callback: (response: Response) -> Unit) =
        get(accept, Headers.headersOf("Accept-Encoding", "identity"), callback)

    /**
     * Sends a GET request to the resource. Follows up to [MAX_REDIRECTS] redirects.
     *
     * Note: Add `Accept-Encoding: identity` to [headers] if you want to disable compression
     * (compression might change the returned ETag).
     *
     * @param accept   value of `Accept` header (always sent for clarity; use *&#47;* if you don't care)
     * @param headers  additional headers to send with the request
     * @param callback called with server response unless an exception is thrown
     *
     * @throws IOException on I/O error
     * @throws HttpException on HTTP error
     * @throws DavException on HTTPS -> HTTP redirect
     */
    fun get(accept: String, headers: Headers?, callback: (response: Response) -> Unit) {
        followRedirects {
            val request = Request.Builder()
                .get()
                .url(location)

            if (headers != null)
                request.headers(headers)

            // always Accept header
            request.header("Accept", accept)

            httpClient.newCall(request.build()).execute()
        }.use { response ->
            checkStatus(response)
            callback(response)
        }
    }

    /**
     * Sends a GET request to the resource for a specific byte range. Make sure to check the
     * response code: servers may return the whole resource with 200 or partials with 206.
     *
     * Follows up to [MAX_REDIRECTS] redirects.
     *
     * @param accept   value of `Accept` header (always sent for clarity; use *&#47;* if you don't care)
     * @param offset   zero-based index of first byte to request
     * @param size     number of bytes to request
     * @param headers  additional headers to send with the request
     * @param callback called with server response unless an exception is thrown
     *
     * @throws IOException on I/O error
     * @throws HttpException on HTTP error
     * @throws DavException on high-level errors
     */
    @Throws(IOException::class, HttpException::class)
    fun getRange(accept: String, offset: Long, size: Int, headers: Headers? = null, callback: (response: Response) -> Unit) {
        followRedirects {
            val request = Request.Builder()
                .get()
                .url(location)

            if (headers != null)
                request.headers(headers)

            val lastIndex = offset + size - 1
            request
                .header("Accept", accept)
                .header("Range", "bytes=$offset-$lastIndex")

            httpClient.newCall(request.build()).execute()
        }.use { response ->
            checkStatus(response)
            callback(response)
        }
    }

    /**
     * Sends a PUT request to the resource. Follows up to [MAX_REDIRECTS] redirects.
     *
     * When the server returns an ETag, it is stored in response properties.
     *
     * @param body          new resource body to upload
     * @param ifETag        value of `If-Match` header to set, or null to omit
     * @param ifScheduleTag value of `If-Schedule-Tag-Match` header to set, or null to omit
     * @param ifNoneMatch   indicates whether `If-None-Match: *` ("don't overwrite anything existing") header shall be sent
     * @param callback      called with server response unless an exception is thrown
     *
     * @throws IOException on I/O error
     * @throws HttpException on HTTP error
     * @throws DavException on HTTPS -> HTTP redirect
     */
    @Throws(IOException::class, HttpException::class)
    fun put(body: RequestBody, ifETag: String? = null, ifScheduleTag: String? = null, ifNoneMatch: Boolean = false, callback: (Response) -> Unit) {
        followRedirects {
            val builder = Request.Builder()
                    .put(body)
                    .url(location)

            if (ifETag != null)
                // only overwrite specific version
                builder.header("If-Match", QuotedStringUtils.asQuotedString(ifETag))
            if (ifScheduleTag != null)
                // only overwrite specific version
                builder.header("If-Schedule-Tag-Match", QuotedStringUtils.asQuotedString(ifScheduleTag))
            if (ifNoneMatch)
                // don't overwrite anything existing
                builder.header("If-None-Match", "*")

            httpClient.newCall(builder.build()).execute()
        }.use { response ->
            checkStatus(response)
            callback(response)
        }
    }

    /**
     * Sends a DELETE request to the resource. Warning: Sending this request to a collection will
     * delete the collection with all its contents!
     *
     * Follows up to [MAX_REDIRECTS] redirects.
     *
     * @param ifETag        value of `If-Match` header to set, or null to omit
     * @param ifScheduleTag value of `If-Schedule-Tag-Match` header to set, or null to omit
     * @param callback      called with server response unless an exception is thrown
     *
     * @throws IOException on I/O error
     * @throws HttpException on HTTP errors, or when 207 Multi-Status is returned
     *         (because then there was probably a problem with a member resource)
     * @throws DavException on HTTPS -> HTTP redirect
     */
    @Throws(IOException::class, HttpException::class)
    fun delete(ifETag: String? = null, ifScheduleTag: String? = null, callback: (Response) -> Unit) {
        followRedirects {
            val builder = Request.Builder()
                    .delete()
                    .url(location)
            if (ifETag != null)
                builder.header("If-Match", QuotedStringUtils.asQuotedString(ifETag))
            if (ifScheduleTag != null)
                builder.header("If-Schedule-Tag-Match", QuotedStringUtils.asQuotedString(ifScheduleTag))

            httpClient.newCall(builder.build()).execute()
        }.use { response ->
            checkStatus(response)

            if (response.code == HTTP_MULTISTATUS)
                /* If an error occurs deleting a member resource (a resource other than
                   the resource identified in the Request-URI), then the response can be
                   a 207 (Multi-Status). […] (RFC 4918 9.6.1. DELETE for Collections) */
                throw HttpException(response)

            callback(response)
        }
    }

    /**
     * Sends a PROPFIND request to the resource. Expects and processes a 207 Multi-Status response.
     *
     * Follows up to [MAX_REDIRECTS] redirects.
     *
     * @param depth    "Depth" header to send (-1 for `infinity`)
     * @param reqProp  properties to request
     * @param callback called for every XML response element in the Multi-Status response
     *
     * @throws IOException on I/O error
     * @throws HttpException on HTTP error
     * @throws DavException on WebDAV error (like no 207 Multi-Status response) or HTTPS -> HTTP redirect
     */
    @Throws(IOException::class, HttpException::class, DavException::class)
    fun propfind(depth: Int, vararg reqProp: Property.Name, callback: DavResponseCallback) {
        // build XML request body
        val serializer = XmlUtils.newSerializer()
        val writer = StringWriter()
        serializer.setOutput(writer)
        serializer.setPrefix("", XmlUtils.NS_WEBDAV)
        serializer.setPrefix("CAL", XmlUtils.NS_CALDAV)
        serializer.setPrefix("CARD", XmlUtils.NS_CARDDAV)
        serializer.startDocument("UTF-8", null)
        serializer.insertTag(PROPFIND) {
            insertTag(PROP) {
                for (prop in reqProp)
                    insertTag(prop)
            }
        }
        serializer.endDocument()

        followRedirects {
            httpClient.newCall(Request.Builder()
                    .url(location)
                    .method("PROPFIND", writer.toString().toRequestBody(MIME_XML))
                    .header("Depth", if (depth >= 0) depth.toString() else "infinity")
                    .build()).execute()
        }.use {
            processMultiStatus(it, callback)
        }
    }

    /**
     * Sends a SEARCH request with the given body to the server.
     *
     * Follows up to [MAX_REDIRECTS] redirects.
     *
     * @param search    search request body (XML format)
     * @param callback  called for every XML response element in the Multi-Status response
     *
     * @throws IOException on I/O error
     * @throws HttpException on HTTP error
     * @throws DavException on WebDAV error (like no 207 Multi-Status response) or HTTPS -> HTTP redirect
     */
    fun search(search: String, callback: (at.bitfire.dav4jvm.Response, at.bitfire.dav4jvm.Response.HrefRelation) -> Unit) {
        followRedirects {
            httpClient.newCall(Request.Builder()
                    .url(location)
                    .method("SEARCH", search.toRequestBody(MIME_XML))
                    .build()).execute()
        }.use {
            processMultiStatus(it, callback)
        }
    }
    
    /**
     * Sends a PROPPATCH request to the server in order to set and remove properties.
     *
     * @param setProperties     map of properties that shall be set (values currently have to be strings)
     * @param removeProperties  list of names of properties that shall be removed
     * @param callback  called for every XML response element in the Multi-Status response
     *
     * Follows up to [MAX_REDIRECTS] redirects.
     *
     * @throws IOException on I/O error
     * @throws HttpException on HTTP error
     * @throws DavException on WebDAV error (like no 207 Multi-Status response) or HTTPS -> HTTP redirect
     */
    fun propPatch(
        setProperties: Map<Property.Name, String>,
        removeProperties: List<Property.Name>,
        callback: (at.bitfire.dav4jvm.Response, at.bitfire.dav4jvm.Response.HrefRelation) -> Unit
    ) {
        followRedirects {
            val rqBody = createPropPatchXml(setProperties, removeProperties)

            httpClient.newCall(
                Request.Builder()
                    .url(location)
                    .method("PROPPATCH", rqBody.toRequestBody(MIME_XML))
                    .build()
            ).execute()
        }.use {
            processMultiStatus(it, callback)
        }
    }
    
    private fun createPropPatchXml(
        setProperties: Map<Property.Name, String>,
        removeProperties: List<Property.Name>
    ): String {
        // build XML request body
        val serializer = XmlUtils.newSerializer()
        val writer = StringWriter()
        serializer.setOutput(writer)
        serializer.setPrefix("d", XmlUtils.NS_WEBDAV)
        serializer.startDocument("UTF-8", null)
        serializer.insertTag(PROPERTYUPDATE) {
            // DAV:set
            if (setProperties.isNotEmpty()) {
                serializer.insertTag(SET) {
                    for (prop in setProperties) {
                        serializer.insertTag(PROP) {
                            serializer.insertTag(prop.key) {
                                text(prop.value)
                            }
                        }
                    }
                }
            }

            // DAV:remove
            if (removeProperties.isNotEmpty()) {
                serializer.insertTag(REMOVE) {
                    for (prop in removeProperties) {
                        insertTag(PROP) {
                            insertTag(prop)
                        }
                    }
                }
            }
        }

        serializer.endDocument()
        return writer.toString()
    }


    // status handling

    /**
     * Checks the status from an HTTP response and throws an exception in case of an error.
     *
     * @throws HttpException in case of an HTTP error
     */
    protected fun checkStatus(response: Response) =
            checkStatus(response.code, response.message, response)

    /**
     * Checks the status from an HTTP response and throws an exception in case of an error.
     *
     * @throws HttpException (with XML error names, if available) in case of an HTTP error
     */
    private fun checkStatus(code: Int, message: String?, response: Response?) {
        if (code / 100 == 2)
            // everything OK
            return

        throw when (code) {
            HttpURLConnection.HTTP_UNAUTHORIZED ->
                if (response != null) UnauthorizedException(response) else UnauthorizedException(message)
            HttpURLConnection.HTTP_FORBIDDEN ->
                if (response != null) ForbiddenException(response) else ForbiddenException(message)
            HttpURLConnection.HTTP_NOT_FOUND ->
                if (response != null) NotFoundException(response) else NotFoundException(message)
            HttpURLConnection.HTTP_CONFLICT ->
                if (response != null) ConflictException(response) else ConflictException(message)
            HttpURLConnection.HTTP_PRECON_FAILED ->
                if (response != null) PreconditionFailedException(response) else PreconditionFailedException(message)
            HttpURLConnection.HTTP_UNAVAILABLE ->
                if (response != null) ServiceUnavailableException(response) else ServiceUnavailableException(message)
            else ->
                if (response != null) HttpException(response) else HttpException(code, message)
        }
    }

    /**
     * Send a request and follows up to [MAX_REDIRECTS] redirects.
     *
     * @param sendRequest called to send the request (may be called multiple times)
     *
     * @return response of the last request (whether it is a redirect or not)
     *
     * @throws DavException on HTTPS -> HTTP redirect
     */
    internal fun followRedirects(sendRequest: () -> Response): Response {
        lateinit var response: Response
        for (attempt in 1..MAX_REDIRECTS) {
            response = sendRequest()
            if (response.isRedirect)
                // handle 3xx Redirection
                response.use {
                    val target = it.header("Location")?.let { location.resolve(it) }
                    if (target != null) {
                        log.fine("Redirected, new location = $target")

                        if (location.isHttps && !target.isHttps)
                            throw DavException("Received redirect from HTTPS to HTTP")

                        location = target
                    } else
                        throw DavException("Redirected without new Location")
                }
            else
                break
        }
        return response
    }

    /**
     * Validates a 207 Multi-Status response.
     *
     * @param response will be checked for Multi-Status response
     *
     * @throws DavException if the response is not a Multi-Status response
     */
    fun assertMultiStatus(response: Response) {
        if (response.code != HTTP_MULTISTATUS)
            throw DavException("Expected 207 Multi-Status, got ${response.code} ${response.message}", httpResponse = response)

        val body = response.body ?:
            throw DavException("Received 207 Multi-Status without body", httpResponse = response)

        body.contentType()?.let { mimeType ->
            if (((mimeType.type != "application" && mimeType.type != "text")) || mimeType.subtype != "xml") {
                /* Content-Type is not application/xml or text/xml although that is expected here.
                   Some broken servers return an XML response with some other MIME type. So we try to see
                   whether the response is maybe XML although the Content-Type is something else. */
                try {
                    val firstBytes = ByteArray(XML_SIGNATURE.size)
                    body.source().peek().readFully(firstBytes)
                    if (XML_SIGNATURE.contentEquals(firstBytes)) {
                        Dav4jvm.log.warning("Received 207 Multi-Status that seems to be XML but has MIME type $mimeType")

                        // response is OK, return and do not throw Exception below
                        return
                    }
                } catch (e: Exception) {
                    Dav4jvm.log.log(Level.WARNING, "Couldn't scan for XML signature", e)
                }

                throw DavException("Received non-XML 207 Multi-Status", httpResponse = response)
            }
        } ?: log.warning("Received 207 Multi-Status without Content-Type, assuming XML")
    }


    // Multi-Status handling

    /**
     * Processes a Multi-Status response.
     *
     * @param response response which is expected to contain a Multi-Status response
     * @param callback called for every XML response element in the Multi-Status response
     *
     * @return list of properties which have been received in the Multi-Status response, but
     * are not part of response XML elements (like `sync-token` which is returned as [SyncToken])
     *
     * @throws IOException on I/O error
     * @throws HttpException on HTTP error
     * @throws DavException on WebDAV error (for instance, when the response is not a Multi-Status response)
     */
    protected fun processMultiStatus(response: Response, callback: DavResponseCallback): List<Property> {
        checkStatus(response)
        assertMultiStatus(response)
        response.body!!.use {
            return processMultiStatus(it.charStream(), callback)
        }
    }

    /**
     * Processes a Multi-Status response.
     *
     * @param reader   the Multi-Status response is read from this
     * @param callback called for every XML response element in the Multi-Status response
     *
     * @return list of properties which have been received in the Multi-Status response, but
     * are not part of response XML elements (like `sync-token` which is returned as [SyncToken])
     *
     * @throws IOException on I/O error
     * @throws HttpException on HTTP error
     * @throws DavException on WebDAV error (like an invalid XML response)
     */
    protected fun processMultiStatus(reader: Reader, callback: DavResponseCallback): List<Property> {
        val responseProperties = mutableListOf<Property>()
        val parser = XmlUtils.newPullParser()

        fun parseMultiStatus(): List<Property> {
            // <!ELEMENT multistatus (response*, responsedescription?,
            //                        sync-token?) >
            val depth = parser.depth
            var eventType = parser.eventType
            while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
                if (eventType == XmlPullParser.START_TAG && parser.depth == depth + 1)
                    when (parser.propertyName()) {
                        DavResponse.RESPONSE ->
                            at.bitfire.dav4jvm.Response.parse(parser, location, callback)
                        SyncToken.NAME ->
                            XmlUtils.readText(parser)?.let {
                                responseProperties += SyncToken(it)
                            }
                    }
                eventType = parser.next()
            }

            return responseProperties
        }

        try {
            parser.setInput(reader)

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.depth == 1)
                    if (parser.propertyName() == DavResponse.MULTISTATUS)
                        return parseMultiStatus()
                // ignore further <multistatus> elements
                eventType = parser.next()
            }

            throw DavException("Multi-Status response didn't contain multistatus XML element")

        } catch (e: EOFException) {
            throw DavException("Incomplete multistatus XML element", e)
        } catch (e: XmlPullParserException) {
            throw DavException("Couldn't parse multistatus XML element", e)
        }
    }

}

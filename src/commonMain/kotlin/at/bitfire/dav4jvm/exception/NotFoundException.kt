/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package at.bitfire.dav4jvm.exception

import io.ktor.client.statement.*
import io.ktor.http.*

class NotFoundException internal constructor(statusCode: HttpStatusCode, exceptionData: ExceptionData) :
    HttpException(statusCode, exceptionData) {

    companion object {
        suspend operator fun invoke(httpResponse: HttpResponse) =
            NotFoundException(httpResponse.status, createExceptionData(httpResponse))
    }

}

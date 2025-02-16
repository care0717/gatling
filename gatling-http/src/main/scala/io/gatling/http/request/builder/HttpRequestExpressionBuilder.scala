/*
 * Copyright 2011-2022 GatlingCorp (https://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.http.request.builder

import java.{ util => ju }

import scala.jdk.CollectionConverters._

import io.gatling.commons.validation._
import io.gatling.core.body._
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.session._
import io.gatling.http.cache.{ ContentCacheEntry, Http2PriorKnowledgeSupport, HttpCaches }
import io.gatling.http.client.{ Param, Request, RequestBuilder => ClientRequestBuilder }
import io.gatling.http.client.body.bytearray.ByteArrayRequestBodyBuilder
import io.gatling.http.client.body.file.FileRequestBodyBuilder
import io.gatling.http.client.body.form.FormUrlEncodedRequestBodyBuilder
import io.gatling.http.client.body.is.InputStreamRequestBodyBuilder
import io.gatling.http.client.body.multipart.{ MultipartFormDataRequestBodyBuilder, Part, StringPart }
import io.gatling.http.client.body.string.StringRequestBodyBuilder
import io.gatling.http.client.body.stringchunks.StringChunksRequestBodyBuilder
import io.gatling.http.protocol.{ HttpProtocol, Remote }
import io.gatling.http.request.BodyPart
import io.gatling.http.util.HttpHelper

import io.netty.handler.codec.http.HttpHeaderNames

object HttpRequestExpressionBuilder {

  private val bodyPartsToMultipartsZero = List.empty[Part[_]].success

  @SuppressWarnings(Array("org.wartremover.warts.ListAppend"))
  private def bodyPartsToMultiparts(bodyParts: List[BodyPart], session: Session): Validation[List[Part[_]]] =
    bodyParts.foldLeft(bodyPartsToMultipartsZero) { (acc, bodyPart) =>
      for {
        accValue <- acc
        value <- bodyPart.toMultiPart(session)
      } yield accValue :+ value
    }
}

class HttpRequestExpressionBuilder(
    commonAttributes: CommonAttributes,
    httpAttributes: HttpAttributes,
    httpCaches: HttpCaches,
    httpProtocol: HttpProtocol,
    configuration: GatlingConfiguration
) extends RequestExpressionBuilder(commonAttributes, httpCaches, httpProtocol, configuration) {

  import RequestExpressionBuilder._

  private def mergeFormParamsAndFormIntoParamJList(
      params: List[HttpParam],
      formMaybe: Option[Expression[Map[String, Any]]],
      session: Session
  ): Validation[ju.List[Param]] = {
    val formParams = resolveParamJList(params, session)

    formMaybe match {
      case Some(form) =>
        for {
          resolvedFormParams <- formParams
          resolvedForm <- form(session)
        } yield {
          val formParamsByName = resolvedFormParams.asScala.groupBy(_.getName)
          val formFieldsByName: Map[String, Seq[Param]] =
            resolvedForm.map { case (key, value) =>
              value match {
                case multipleValues: Seq[_] => key -> multipleValues.map(value => new Param(key, value.toString))
                case monoValue              => key -> Seq(new Param(key, monoValue.toString))
              }
            }
          // override form with formParams
          val javaParams: ju.List[Param] = (formFieldsByName ++ formParamsByName).values.flatten.toSeq.asJava
          javaParams
        }

      case _ =>
        formParams
    }
  }

  private def configureMultipartFormData(session: Session, requestBuilder: ClientRequestBuilder): Validation[ClientRequestBuilder] =
    for {
      params <- mergeFormParamsAndFormIntoParamJList(httpAttributes.formParams, httpAttributes.form, session)
      stringParts = params.asScala.map(param => new StringPart(param.getName, param.getValue, charset, null, null, null, null, null))
      parts <- HttpRequestExpressionBuilder.bodyPartsToMultiparts(httpAttributes.bodyParts, session)
    } yield requestBuilder.setBodyBuilder(new MultipartFormDataRequestBodyBuilder((stringParts ++ parts).asJava))

  private def configureFormUrlEncoded(session: Session, requestBuilder: ClientRequestBuilder): Validation[ClientRequestBuilder] =
    for {
      params <- mergeFormParamsAndFormIntoParamJList(httpAttributes.formParams, httpAttributes.form, session)
    } yield requestBuilder.setBodyBuilder(new FormUrlEncodedRequestBodyBuilder(params))

  private def setBody(session: Session, requestBuilder: ClientRequestBuilder, body: Body): Validation[ClientRequestBuilder] =
    body match {
      case StringBody(string, _) => string(session).map(s => requestBuilder.setBodyBuilder(new StringRequestBodyBuilder(s)))
      case RawFileBody(resourceWithCachedBytes) =>
        resourceWithCachedBytes(session).map { case ResourceAndCachedBytes(resource, cachedBytes) =>
          val requestBodyBuilder = cachedBytes match {
            case Some(bytes) => new ByteArrayRequestBodyBuilder(bytes, resource.name)
            case _           => new FileRequestBodyBuilder(resource.file)
          }
          requestBuilder.setBodyBuilder(requestBodyBuilder)
        }
      case ByteArrayBody(bytes) => bytes(session).map(b => requestBuilder.setBodyBuilder(new ByteArrayRequestBodyBuilder(b, null)))
      case body: ElBody         => body.asStringWithCachedBytes(session).map(chunks => requestBuilder.setBodyBuilder(new StringChunksRequestBodyBuilder(chunks.asJava)))
      case InputStreamBody(is)  => is(session).map(is => requestBuilder.setBodyBuilder(new InputStreamRequestBodyBuilder(is)))
    }

  private def configureBody(session: Session, requestBuilder: ClientRequestBuilder): Validation[ClientRequestBuilder] = {
    require(httpAttributes.body.isEmpty || httpAttributes.bodyParts.isEmpty, "Can't have both a body and body parts!")

    httpAttributes.body match {
      case Some(body) =>
        setBody(session, requestBuilder, body)
      case _ =>
        val hasParts = httpAttributes.bodyParts.nonEmpty
        val hasForm = httpAttributes.formParams.nonEmpty || httpAttributes.form.nonEmpty
        val hadExplicitFormDataContentType = HttpHelper.isMultipartFormData(requestBuilder.getContentType)

        if (hasParts || (hasForm && hadExplicitFormDataContentType)) {
          configureMultipartFormData(session, requestBuilder)
        } else if (hasForm) {
          configureFormUrlEncoded(session, requestBuilder)
        } else {
          requestBuilder.success
        }
    }
  }

  private val configurePriorKnowledge: RequestBuilderConfigure = {
    if (httpProtocol.enginePart.enableHttp2) { session => requestBuilder =>
      val http2PriorKnowledge = Http2PriorKnowledgeSupport.isHttp2PriorKnowledge(session, Remote(requestBuilder.getUri))
      requestBuilder
        .setHttp2Enabled(true)
        .setAlpnRequired(http2PriorKnowledge.forall(_ == true)) // ALPN is necessary only if we know that this remote is using HTTP/2 or if we still don't know
        .setHttp2PriorKnowledge(http2PriorKnowledge.contains(true))
        .success
    } else {
      ConfigureIdentity
    }
  }

  override protected def configureRequestTimeout(requestBuilder: ClientRequestBuilder): Unit =
    requestBuilder.setRequestTimeout(httpAttributes.requestTimeout.getOrElse(configuration.http.requestTimeout).toMillis)

  override protected def configureRequestBuilderForProtocol: RequestBuilderConfigure =
    session =>
      requestBuilder =>
        configureBody(session, requestBuilder)
          .flatMap(configurePriorKnowledge(session))

  private def configureCachingHeaders(session: Session)(request: Request): Request = {
    httpCaches.contentCacheEntry(session, request).foreach { case ContentCacheEntry(_, etag, lastModified) =>
      etag.foreach(request.getHeaders.set(HttpHeaderNames.IF_NONE_MATCH, _))
      lastModified.foreach(request.getHeaders.set(HttpHeaderNames.IF_MODIFIED_SINCE, _))
    }
    request
  }

  // hack because we need the request with the final uri
  override def build: Expression[Request] = {
    val exp = super.build
    if (httpProtocol.requestPart.cache) { session =>
      exp(session).map(configureCachingHeaders(session))
    } else {
      exp
    }
  }
}

/*
 * Copyright 2014-2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.atlas.akka

import akka.actor.ActorRefFactory
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.ByteString
import com.netflix.atlas.json.Json
import org.scalatest.FunSuite


class CustomDirectivesSuite extends FunSuite with ScalatestRouteTest {

  import CustomDirectivesSuite._

  class TestService(val actorRefFactory: ActorRefFactory) {

    def routes: Route = {
      CustomDirectives.accessLog {
        CustomDirectives.corsFilter {
          CustomDirectives.jsonpFilter {
            path("text") {
              get {
                val entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, "text response")
                complete(HttpResponse(status = StatusCodes.OK, entity = entity))
              }
            } ~
              path("json") {
                get {
                  val entity = HttpEntity(MediaTypes.`application/json`, "[1,2,3]")
                  complete(HttpResponse(status = StatusCodes.OK, entity = entity))
                } ~
                post {
                  CustomDirectives.parseEntity(CustomDirectives.json[Message]) { message =>
                    val entity = HttpEntity(MediaTypes.`application/json`, Json.encode(message))
                    complete(HttpResponse(status = StatusCodes.OK, entity = entity))
                  }
                }
              } ~
              path("binary") {
                get {
                  val data = ByteString("text response")
                  val entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, data)
                  complete(HttpResponse(status = StatusCodes.OK, entity = entity))
                }
              } ~
              path("error") {
                get {
                  val entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, "error")
                  complete(HttpResponse(status = StatusCodes.BadRequest, entity = entity))
                }
              } ~
              path("empty") {
                get {
                  val headers = List(RawHeader("foo", "bar"))
                  complete(HttpResponse(status = StatusCodes.OK, headers = headers))
                }
              }
          }
        }
      }
    }
  }

  val endpoint = new TestService(system)

  test("text") {
    Get("/text") ~> endpoint.routes ~> check {
      val expected = """text response"""
      assert(expected === responseAs[String])
    }
  }

  test("json") {
    Get("/json") ~> endpoint.routes ~> check {
      val expected = """[1,2,3]"""
      assert(expected === responseAs[String])
    }
  }

  test("json post") {
    val msg = Message("foo", "bar baz")
    Post("/json", Json.encode(msg)) ~> endpoint.routes ~> check {
      val expected = Json.decode[Message](responseAs[String])
      assert(expected === msg)
    }
  }

  test("smile post") {
    val msg = Message("foo", "bar baz")
    val entity = HttpEntity(CustomMediaTypes.`application/x-jackson-smile`, Json.smileEncode(msg))
    Post("/json", entity) ~> endpoint.routes ~> check {
      val expected = Json.decode[Message](responseAs[String])
      assert(expected === msg)
    }
  }

  test("binary") {
    Get("/binary") ~> endpoint.routes ~> check {
      val expected = """text response"""
      assert(expected === responseAs[String])
    }
  }

  test("jsonp with text") {
    Get("/text?callback=foo") ~> endpoint.routes ~> check {
      val expected = """foo({"status":200,"headers":{"content-type":["text/plain"]},"body":"text response"})"""
      assert(expected === responseAs[String])
    }
  }

  test("jsonp with json") {
    Get("/json?callback=foo") ~> endpoint.routes ~> check {
      val expected = """foo({"status":200,"headers":{"content-type":["application/json"]},"body":[1,2,3]})"""
      assert(expected === responseAs[String])
    }
  }

  test("jsonp with binary") {
    Get("/binary?callback=foo") ~> endpoint.routes ~> check {
      val expected = """foo({"status":200,"headers":{"content-type":["application/octet-stream"]},"body":"dGV4dCByZXNwb25zZQ=="})"""
      assert(expected === responseAs[String])
    }
  }

  test("jsonp with 400") {
    Get("/error?callback=foo") ~> endpoint.routes ~> check {
      val expected = """foo({"status":400,"headers":{"content-type":["text/plain"]},"body":"error"})"""
      assert(StatusCodes.OK === response.status)
      assert(expected === responseAs[String])
    }
  }

  test("jsonp with empty response body and custom header") {
    Get("/empty?callback=foo") ~> endpoint.routes ~> check {
      val expected = """foo({"status":200,"headers":{"foo":["bar"],"content-type":["text/plain"]},"body":""})"""
      assert(StatusCodes.OK === response.status)
      assert(expected === responseAs[String])
    }
  }

  test("cors") {
    val headers = List(Origin(HttpOrigin("http://localhost")))
    val req = HttpRequest(HttpMethods.GET, Uri("/json"), headers)
    req ~> endpoint.routes ~> check {
      assert(headers.nonEmpty)
      response.headers.foreach {
        case `Access-Control-Allow-Origin`(v) =>
          assert("http://localhost" === v.toString)
        case `Access-Control-Allow-Methods`(vs) =>
          assert("GET,PATCH,POST,PUT,DELETE" === vs.map(_.name()).mkString(","))
        case h =>
          fail(s"unexpected header: $h")
      }
      val expected = """[1,2,3]"""
      assert(expected === responseAs[String])
    }
  }

  test("cors with custom header") {
    val headers = List(Origin(HttpOrigin("http://localhost")))
    val req = HttpRequest(HttpMethods.GET, Uri("/empty"), headers)
    req ~> endpoint.routes ~> check {
      assert(headers.nonEmpty)
      response.headers.foreach {
        case `Access-Control-Allow-Origin`(v) =>
          assert("http://localhost" === v.toString)
        case `Access-Control-Allow-Methods`(vs) =>
          assert("GET,PATCH,POST,PUT,DELETE" === vs.map(_.name()).mkString(","))
        case `Access-Control-Expose-Headers`(vs) =>
          assert("foo" === vs.mkString(","))
        case h: RawHeader if h.lowercaseName == "foo" =>
          assert("bar" === h.value)
        case h =>
          fail(s"unexpected header: $h (${h.getClass})")
      }
      val expected = ""
      assert(expected === responseAs[String])
    }
  }

  test("cors with custom request headers") {
    val headers = List(
      Origin(HttpOrigin("http://localhost")),
      `Access-Control-Request-Headers`("foo"))
    val req = HttpRequest(HttpMethods.GET, Uri("/json"), headers)
    req ~> endpoint.routes ~> check {
      assert(headers.nonEmpty)
      response.headers.foreach {
        case `Access-Control-Allow-Origin`(v) =>
          assert("http://localhost" === v.toString)
        case `Access-Control-Allow-Methods`(vs) =>
          assert("GET,PATCH,POST,PUT,DELETE" === vs.map(_.name()).mkString(","))
        case `Access-Control-Allow-Headers`(vs) =>
          assert("foo" === vs.mkString(","))
        case h =>
          fail(s"unexpected header: $h")
      }
      val expected = """[1,2,3]"""
      assert(expected === responseAs[String])
    }
  }

  // Some browsers send this when a request is made from a file off the local filesystem
  test("cors null origin") {
    val headers = List(RawHeader("Origin", "null"))
    val req = HttpRequest(HttpMethods.GET, Uri("/json"), headers)
    req ~> endpoint.routes ~> check {
      assert(headers.nonEmpty)
      response.headers.foreach {
        case `Access-Control-Allow-Origin`(v) =>
          assert("*" === v.toString)
        case `Access-Control-Allow-Methods`(vs) =>
          assert("GET,PATCH,POST,PUT,DELETE" === vs.map(_.name()).mkString(","))
        case h =>
          fail(s"unexpected header: $h")
      }
      val expected = """[1,2,3]"""
      assert(expected === responseAs[String])
    }
  }
}

object CustomDirectivesSuite {
  case class Message(subject: String, body: String)
}

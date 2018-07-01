package io.appalachian.vapor.vapord

import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import scalatags.Text.all._

package object templates {
  object Implicits {
    implicit val scalaTagsMarshaller: ToEntityMarshaller[Modifier] =
      Marshaller.withOpenCharset(MediaTypes.`text/html`) { (value, charset) =>
        HttpEntity(ContentType(MediaTypes.`text/html`, charset), "<!DOCTYPE html>\n" + value.toString)
      }
  }
}

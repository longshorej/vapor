package io.appalachian.vapor.vapord.templates

import scala.collection.immutable.Seq
import scalatags.Text.all.{ content => contentAttr, head => headTag, _ }
import scalatags.Text.tags2

object page {
  def apply(head: Modifier,
            content: Modifier): Modifier = html(lang := "en",
    headTag(
      head,
      link(rel := "stylesheet", `type` := "text/css", href := "/assets/main.css"),
      link(rel := "stylesheet", `type` := "text/css", href := "/webjars/morris.js/0.5.0/morris.css"),
      script(src := "/webjars/react/16.6.3/cjs/react.production.min.js"),
      script(src := "/assets/main.js"),
      meta(name := "veiwport", contentAttr := "width=device-width, initial-scale=1")
    ),
    body(
      div(id := "header", div(`class` := "container", "vapord")),
      div(id := "content", div(`class` := "container", content)),
      div(id := "footer", div(`class` := "container")),
    )
  )
}


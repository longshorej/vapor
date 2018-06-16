package com.jasonlongshore.vapor.templates

import com.jasonlongshore.vapor._
import scala.collection.immutable.Seq
import scalatags.Text.all._
import scalatags.Text.tags2

object listing {
  def apply(): Modifier = page(
    head = Seq(
      tags2.title("Vapor")
    ),
    content = div(id := "listing",
      input(id := "search", autofocus := "autofocus"),
      div(id := "charts"),
      script(
        raw("""
          (function() {
            vapor.init();

            return;

            for (let i = 0; i < 10; i++) {
              vapor.renderChart(`my-chart-${i}`, [
                { year: '2008', value: 20 },
                { year: '2009', value: 10 },
                { year: '2010', value: 5 },
                { year: '2011', value: 5 },
                { year: '2012', value: 20 }
              ]);
            }
          })();
        """)
      )
    )
  )
}


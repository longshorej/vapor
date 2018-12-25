window.vapor = (function() {
  const chartsData = {};
  const chartsStore = {};

  const refreshChart = (name) => {
    fetch(`api/charts/${name}`).then(response => {
      response.json().then(chart => {
        renderChart(chart.name, chart.data);
      });
    });
  };

  const renderChart = (name, data) => {
    if (chartsStore[name] !== undefined) {
      if (chartsData[name] != data) {
        chartsData[name] = data;
        chartsStore[name].setData(data);
      }
    } else {
      const charts = document.getElementById('charts');

      const elementContainer = document.createElement('div');
      elementContainer.className = 'chart-container';

      const element = document.createElement('div');
      element.id = `chart-${name}`;
      element.className = 'chart';
      elementContainer.appendChild(element);
      charts.appendChild(elementContainer);

      const title = document.createElement('div');
      title.className = 'chart-title';
      title.innerHTML = escape(name);
      elementContainer.appendChild(title);

      const refresh = document.createElement('button');
      refresh.className = 'refresh';
      refresh.innerHTML = escape('R');
      //elementContainer.appendChild(refresh);
      refresh.addEventListener('click', (e) => {
        refreshChart(name);
      });

      const chart = new Morris.Area({
        element: element.id,
        lineColors: ["#288C00"],
        pointFillColors: ["#CCCCCC"],
        pointSize: 0,
        pointStrokeColors: ["#CCCCCC"],
        data: data,
        xkey: "when",
        ykeys: ["value"],
        labels: ['Value'],
        fillOpacity: 0.8,
        hideHover: true,
        dateFormat: (x) => new Date(x).toLocaleString()
      });

      chartsStore[name] = chart;

      refreshChart(name);

      setInterval(() => refreshChart(name), 5000);
    }
  };

  const init = () => {
    const rendered = {};

    setInterval(() =>
      fetch("api/charts").then(response => {
        response.json().then(json => {
          let data = json;

          for (const chart of data.charts) {
            if (!rendered[chart.name]) {
              renderChart(chart.name, []);
              rendered[chart.name] = true;
            }
          }

          // @TODO sort DOM so chart order always same
          // @TODO remove old charts
        });
      }),

      1000
    );

    document.getElementById("search").addEventListener("change", e => {
    });
  };


  return {
    init: init,
    renderChart: renderChart
  };

})();

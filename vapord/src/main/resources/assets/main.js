window.vapor = (function() {
  const chartsStore = {};

  const refreshChart = (name) => {
    fetch(`api/charts/${name}`).then(response => {
      response.json().then(chart => {
        console.log(chart);
        renderChart(chart.name, chart.data);
      });
    });
  };

  const renderChart = (name, data) => {
    if (chartsStore[name] !== undefined) {
      chartsStore[name].setData(data);
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
      elementContainer.appendChild(refresh);
      refresh.addEventListener('click', (e) => {
        refreshChart(name);
      });

      const chart = new Morris.Area({
        element: element.id,
        lineColors: ["#288C00"],
        pointFillColors: ["#CCCCCC"],
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
    }
  };

  const init = () => {
    fetch("api/charts").then(response => {
      response.json().then(json => {
        let data = json;

        for (const chart of data.charts) {
          renderChart(chart.name, []);
        }
      });
    });

    document.getElementById("search").addEventListener("change", e => {
    });
  };


  return {
    init: init,
    renderChart: renderChart
  };

})();

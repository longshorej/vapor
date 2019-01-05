import React from 'react';

import classNames from 'classnames';
import moment from 'moment';

import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  LineChart,
  Legend,
  Line,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip
} from 'recharts';

import Dropdown from './Dropdown';

const gaugeEntries = [];

const TIME_LIMIT_MS = 300000;

const sectionName = (gaugeName) => {
  const components = gaugeName.split('.');

  if (components.length > 0) {
    return components.length > 1 ? components[0] + '.' : components[0];
  } else {
    return null;
  }
};

const extractLocationPeriod = (hash) => {
  const location = hash && hash.startsWith('#') ?
    hash.substring(1) : hash;

  const parts = location.split('/');

  const components = parts[0].split('.');

  const period = parts.length > 1 ? parts[1] : null;

  const section = components.length > 1 ? components[0] + '.' : components[0];

  const seriesSeparator = parts[0].indexOf('#');

  const seriesName = seriesSeparator != -1 ?
    parts[0].substring(seriesSeparator + 1) : null;

  const gaugeName = seriesSeparator != -1 ?
    parts[0].substring(0, seriesSeparator) : parts[0];

  return {
    section: section.length === 0 ? null : section,
    gaugeName: gaugeName.length === 0 ? null : gaugeName,
    seriesName: seriesName === null || seriesName.length === 0 ? null : seriesName,
    period: period === null || period.length === 0 ? null : period
  };
};

export default class extends React.Component {
  constructor(props) {
    super(props);

    this.state = {
      namesDisconnected: false,
      gaugeEntriesDisconnected: false,
      data: undefined,
      gaugeName: null,
      location: '',
      period: 'live',
      periodDropdownActive: false,
      section: null,
      seriesName: null,
      sections: {},
      timestamp: new Date().getTime()
    };

    this.gaugeEntriesEventSource = null;
    this.namesEventSource = null;

    this.handleClean = this.handleClean.bind(this);
    this.handleGaugeEntry = this.handleGaugeEntry.bind(this);
    this.handleGaugeNameAdded = this.handleGaugeNameAdded.bind(this);
    this.handleGaugeNameRemoved = this.handleGaugeNameRemoved.bind(this);
    this.handleHashchange = this.handleHashchange.bind(this);
    this.handlePeriodClick = this.handlePeriodClick.bind(this);
    this.handlePeriodDropdownClick = this.handlePeriodDropdownClick.bind(this);
    this.handleTick = this.handleTick.bind(this);
    this.setupGaugeEntries = this.setupGaugeEntries.bind(this);
    this.setupNames = this.setupNames.bind(this);
  }

  setupGaugeEntries() {
    this.setState(state => {
      if (this.state.period !== 'live') {
        state.timestamp = new Date().getTime();
      }

      state.data = undefined;

      return state;
    }, () => {
      if (this.gaugeEntriesEventSource !== null) {
        try {
          this.gaugeEntriesEventSource.close();
        } catch (e) {}

        this.gaugeEntriesEventSource = null;
      }

      const gaugeName = this.state.gaugeName;

      if (gaugeName === null) {
        return;
      }

      const baseUrl = '/api/gauge-entries/' + encodeURIComponent(gaugeName);

      const url = this.state.period === 'live' ? baseUrl : `${baseUrl}/${this.state.period}`;

      this.gaugeEntriesEventSource = new EventSource(url);

      const gaugeEntriesEventSource = this.gaugeEntriesEventSource;

      this.gaugeEntriesEventSource.onopen = () => {
        this.setState({ gaugeEntriesDisconnected: false });
      };

      this.gaugeEntriesEventSource.onerror = () => {
        if (this.state.period === 'live') {
          this.setState({ gaugeEntriesDisconnected: true }, () => {
            setTimeout(() => this.setupGaugeEntries(gaugeName), 3000);
          });
        } else {
          gaugeEntriesEventSource.close();
        }
      };

      this.gaugeEntriesEventSource.addEventListener('GaugeEntry', this.handleGaugeEntry);
    });
  }

  setupNames(resetSections) {
    this.setState(
      {
        sections: {}
      },

      () => {
        if (this.namesEventSource != null) {
          this.namesEventSource.close();
          this.namesEventSource = null;
        }

        this.namesEventSource = new EventSource('/api/gauge-names');

        this.namesEventSource.onopen = () => {
          this.setState({ namesDisconnected: false });
        };

        this.namesEventSource.onerror = () => {
          this.setState({ sections: {}, namesDisconnected: true }, () => {
            setTimeout(() => this.setupNames(), 3000);
          });
        };

        this.namesEventSource.addEventListener('GaugeNameAdded', this.handleGaugeNameAdded);
        this.namesEventSource.addEventListener('GaugeNameRemoved', this.handleGaugeNameRemoved);
      }
    );
  }

  componentDidMount() {
    this.setupNames();

    window.addEventListener('hashchange', this.handleHashchange, false);
    this.cleanInterval = window.setInterval(this.handleClean, 10000);
    this.tickInterval = window.setInterval(this.handleTick, 1000 / 15);

    this.handleHashchange();
  }

  componentWillUnmount() {
    if (this.gaugeEntriesEventSource !== null) {
      this.gaugeEntriesEventSource.close();
      this.gaugeEntriesEventSource = null;
    }

    if (this.namesEventSource !== null) {
      this.namesEventSource.close();
      this.namesEventSource = null;
    }

    window.removeEventListener('hashchange', this.handleHashchange, false);
    window.clearInterval(this.cleanInterval);
    window.clearInterval(this.tickInterval);
  }

  handleClean() {
    if (this.state.period !== 'live') {
      return;
    }

    // @FIXME this needs to be in sync with backend somehow (durations)
    this.setState(state => {
      const oldestAllowed = ((1000 * 60 * 5) + (1000 * 10)); // 5 minutes, 10 seconds

      if (state.data !== undefined) {
        for (const section of Object.keys(state.data)) {
          for (const gaugeName of Object.keys(state.data[section].entries)) {
            for (const entryKey of Object.keys(state.data[section].entries[gaugeName])) {
              if (entryKey < oldestAllowed) {
                delete state.data[section].entries[gaugeName][entryKey];
              }
            }
          }
        }
      }
    });
  }

  handleTick() {
    this.setState(state => {
      if (this.state.period === 'live') {
        state.timestamp = new Date().getTime();
      }

      if (state.data === undefined) {
        state.data = {};
      }

      for (const entry of gaugeEntries) {
        if (entry.data) {
          const parsed = JSON.parse(entry.data);

          if (parsed &&
              parsed.name !== undefined &&
              parsed.value !== undefined &&
              parsed.when !== undefined) {
            const extracted = extractLocationPeriod(parsed.name);

            if (extracted.section !== null && extracted.gaugeName !== null) {
              if (state.data[extracted.section] === undefined) {
                state.data[extracted.section] = { active: false, entries: {} };
              }

              if (state.data[extracted.section].entries[extracted.gaugeName] === undefined) {
                state.data[extracted.section].entries[extracted.gaugeName] = {};
              }

              const timestamp = parsed.when;

              const seriesName = extracted.seriesName === null ? extracted.gaugeName : extracted.seriesName;

              if (state.data[extracted.section].entries[extracted.gaugeName][seriesName] === undefined) {
                state.data[extracted.section].entries[extracted.gaugeName][seriesName] = {};
              }

              if (state.data[extracted.section].entries[extracted.gaugeName][seriesName][timestamp] === undefined) {
                state.data[extracted.section].entries[extracted.gaugeName][seriesName][timestamp] = [];
              }

              state.data[extracted.section].entries[extracted.gaugeName][seriesName][timestamp].push(parsed.value);
            }
          }
        }
      }

      gaugeEntries.length = 0;

      return state;
    });
  }

  handleGaugeNameAdded(e) {
    if (e.data) {
        const parsed = JSON.parse(e.data);

        if (parsed && parsed.name !== undefined) {
          const extracted = extractLocationPeriod(parsed.name);

          if (extracted.section !== null && extracted.gaugeName !== null) {
            this.setState(s => {
              if (s.sections[extracted.section] === undefined) {
                s.sections[extracted.section] = { active: false, gaugeNames: [] };
              }

              const gaugeIndex = s.sections[extracted.section].gaugeNames.indexOf(extracted.gaugeName);

              if (gaugeIndex === -1) {
                s.sections[extracted.section].gaugeNames.push(extracted.gaugeName);
                s.sections[extracted.section].gaugeNames.sort((a, b) => a.toLowerCase().localeCompare(b.toLowerCase()));
              }

              return s;
            });
          }
        }
    }
  }

  handleGaugeNameRemoved(e) {
    if (e.data) {
        const parsed = JSON.parse(e.data);

        if (parsed && parsed.name !== undefined) {
          this.setState(s => {
            const extracted = extractLocationPeriod(parsed.name);

            if (extracted.section !== null && extracted.gaugeName !== null && s.sections[extracted.section] !== undefined) {
              const gaugeIndex = s.sections[extracted.section].gaugeNames.indexOf(extracted.gaugeName);

              if (gaugeIndex != -1) {
                s.sections[parsed.section].gaugeNames.splice(gaugeIndex, 1);
              }

              if (s.sections[parsed.section].gaugeNames.length == 0) {
                delete s.sections[parsed.section];
              }
            }

            return s;
          });
        }
    }
  }

  handleGaugeEntry(entry) {
    gaugeEntries.push(entry);
  }

  handleHashchange() {
    const location = window.location.hash && window.location.hash.startsWith('#') ?
      window.location.hash.substring(1) : '';

    this.setState(
      extractLocationPeriod(window.location.hash),
      () => {
        this.setupGaugeEntries();
      }
    );
  }

  handlePeriodClick(period) {
    return (e) => {
      e.preventDefault();

      this.setState({ period: period, periodDropdownActive: false });
    };
  }

  handlePeriodDropdownClick(e) {
    e.preventDefault();
    this.setState(s => { return { periodDropdownActive: !s.periodDropdownActive }; });
  }

  handleSectionClick(name) {
    return (e) => {
        this.setState(state => {
          for (const section of Object.keys(state.sections)) {
            state.sections[section].active = section === name;
          }

          return state;
        }, () => {
          this.setupGaugeEntries();
        });
    };
  }

  render() {
    const keys = Object.keys(this.state.sections);
    const selected = keys.some(k => this.state.section === k);
    const periodTexts = { 'live': 'Live', '1h': '1H', '1d': '1D', '2w': '2W' };

    let domain = [this.state.timestamp - TIME_LIMIT_MS, this.state.timestamp];

    // @FIXME a bit crude
    if (this.state.period === '1h') {
      domain = [this.state.timestamp - (1000 * 60 * 60), this.state.timestamp];
    } else if (this.state.period === '1d') {
      domain = [this.state.timestamp - (1000 * 60 * 60 * 24), this.state.timestamp];
    } else if (this.state.period === '2w') {
      domain = [this.state.timestamp - (1000 * 60 * 24 * 14), this.state.timestamp];
    }

    // @TODO implement others on the backend
    delete periodTexts['1h'];
    delete periodTexts['1d'];
    delete periodTexts['1w'];
    delete periodTexts['2w'];

    return (
        <div id="page">
          <div id="oldwhatremoveit">
            <div className="container-fluid">
              {
                (this.state.namesDisconnected || (this.state.section !== null && this.state.gaugeEntriesDisconnected)) ? (
                  <div className="alert alert-warning no-data" id="no-data">Vapor isn't connected.</div>
                ) : (keys.length < 1 ? (
                  <div className="alert alert-info no-data" id="no-data">Vapor hasn't received any data yet.</div>
                ) : (
                  ""
                ))
              }

              <div className="row">
                <div className="col-md-3" id="navigation">
                  { keys.map(k => {
                    const active = this.state.section === k;
                    const period = this.state.period === null ? 'live' : this.state.period;
                    const link = active ? '#' : (
                      this.state.sections[k].gaugeNames.length > 0 ?
                        `#${this.state.sections[k].gaugeNames[0]}/${period}` : '#'
                    );

                    return (
                      <div key={k} className="section">
                        <div className={classNames("section-picker btn-block btn-group", { "dropdown": active, "dropright": !active })}>
                          <a key={k} href={link} type="button" className="btn btn-block btn-secondary dropdown-toggle">
                            {k}
                          </a>
                        </div>
                        { active && <div className="gauge-name-picker list-group gauge-names">
                          { this.state.sections[k].gaugeNames.map(i =>
                            <a key={i} href={`#${i}/${period}`} className={classNames("list-group-item list-group-item-action", { active: this.state.gaugeName === i })}>
                              {i === k ? i : i.substring(k.length)}
                            </a>
                          )}
                          </div> }
                      </div>
                    );
                  } )}
                </div>
                <div className="col-md-9" id="content">
                  {keys.length > 0 && !selected && <div className="alert alert-info">Select a category to get started.</div>}
                  {keys.filter(k => this.state.section === k && this.state.gaugeName !== null).map(k =>
                      <div key={k}>
                        {[this.state.gaugeName].map(n => {
                          const series = [];

                          if (this.state.data !== undefined && this.state.data[k] !== undefined && this.state.data[k].entries[n] !== undefined) {
                            for (const seriesName of Object.keys(this.state.data[k].entries[n])) {
                              const data = { name: seriesName, entries: [] };

                              for (const when of Object.keys(this.state.data[k].entries[n][seriesName])) {
                                if (when >= domain[0] && when <= domain[1]) {
                                  const sum = this.state.data[k].entries[n][seriesName][when].reduce((a, n) => a + n, 0);
                                  const value = sum / this.state.data[k].entries[n][seriesName][when].length;

                                  data.entries.push({
                                    when: when,
                                    value: value
                                  });
                                }
                              }

                              series.push(data);
                            }
                          }

                          const colors = [
                            "#0099CC", // blue
                            "#CC0099", // pink
                            "#00CC66", // green
                            "#6900CC", // purple
                            "#C5CC00", // yellow
                            "#CC6D00", // orange
                            "#AAAAAA", // gray
                            "#CC0000"  // red
                          ];

                          return <div key={n} className="card">
                            <div className="card-body">
                              <ResponsiveContainer width='100%' height={300}>
                                <LineChart>
                                  <XAxis
                                    allowDuplicatedCategory={false}
                                    allowDataOverflow={true}
                                    dataKey = 'when'
                                    domain = {domain}
                                    name = 'Time'
                                    tickFormatter = {(unixTime) => moment(parseInt(unixTime, 10)).format('YYYY-MM-DD h:mm:ss a')}
                                    tickCount={2}
                                    tickLine={false}
                                    axisLine={false}
                                    stroke="#FFFFFF"
                                    interval="preserveStartEnd"
                                    type = 'number'
                                  />
                                  <YAxis />

                                  <CartesianGrid strokeDasharray="3 3" />
                                  <Tooltip labelFormatter={(label) => moment(parseInt(label, 10)).format('YYYY-MM-DD h:mm:ss a')} />
                                  <Legend verticalAlign="top" height={36}/>
                                  {series.map((data, index) =>
                                    <Line
                                      dot={false}
                                      isAnimationActive={false}
                                      stroke={colors[index % colors.length]}
                                      strokeWidth={2}
                                      dataKey="value"
                                      data={data.entries}
                                      name={data.name}
                                      key={data.name} /> )}

                                </LineChart>
                              </ResponsiveContainer>
                              <div className="text-center">
                                <div className="btn-group period-picker" role="group" aria-label="Basic example">
                                  <a href={`#${n}/live`} className={classNames("btn", { "btn-secondary": this.state.period !== null && this.state.period !== 'live', "btn-primary": this.state.period === null || this.state.period === 'live' })}>Live</a>
                                  <a href={`#${n}/1h`} className={classNames("btn", { "btn-secondary": this.state.period !== '1h', "btn-primary": this.state.period === '1h' })}>1H</a>
                                  <a href={`#${n}/1d`} className={classNames("btn", { "btn-secondary": this.state.period !== '1d', "btn-primary": this.state.period === '1d' })}>1D</a>
                                  <a href={`#${n}/2w`} className={classNames("btn", { "btn-secondary": this.state.period !== '2w', "btn-primary": this.state.period === '2w' })}>2W</a>
                                </div>
                              </div>
                            </div>
                          </div>;
                        })}
                      </div>
                    )}
                  </div>
                </div>
            </div>
          </div>
        </div>
    );
  }

}

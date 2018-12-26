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

const TIME_LIMIT_MS = 300000;


export default class extends React.Component {
  constructor(props) {
    super(props);

    this.state = {
      data: {},
      period: 'live',
      periodDropdownActive: false,
      timestamp: new Date().getTime()
    };

    this.handleGaugeEntry = this.handleGaugeEntry.bind(this);
    this.handlePeriodClick = this.handlePeriodClick.bind(this);
    this.handlePeriodDropdownClick = this.handlePeriodDropdownClick.bind(this);
    this.handleTick = this.handleTick.bind(this);
  }

  componentDidMount() {
    this.eventSource = new EventSource("/api/gauge-entries");

    this.eventSource.addEventListener('GaugeEntry', this.handleGaugeEntry);

    this.tickInterval = window.setInterval(this.handleTick, 1000 / 15);
  }

  componentWillUnmount() {
    this.eventSource.removeAllListeners();
    this.eventSource.close();

    window.clearInterval(this.tickInterval);
  }

  handleTick() {
    this.setState({ timestamp: new Date().getTime() });
  }

  handleGaugeEntry(entry) {
    if (entry.data) {
        const parsed = JSON.parse(entry.data);

        if (parsed &&
            parsed.name !== undefined &&
            parsed.value !== undefined &&
            parsed.when !== undefined) {
          const components = parsed.name.split('.');

          if (components.length > 0) {
            const name = components.length > 1 ? components[0] + '.' : components[0];

            this.setState(state => {
              if (state.data[name] === undefined) {
                state.data[name] = { active: false, entries: {} };
              }

              if (state.data[name].entries[parsed.name] === undefined) {
                state.data[name].entries[parsed.name] = [];
              }

              state.data[name].entries[parsed.name].push({
                when: parsed.when * 1000,
                value: parsed.value
              });

              return state;
            });
          }
        }
    }
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
          if (state.data[name] !== undefined) {
            state.data[name].active = !state.data[name].active;
          }

          return state;
        });
    };
  }

  render() {
    const keys = Object.keys(this.state.data).sort((a, b) => a.toLowerCase().localeCompare(b.toLowerCase()));
    const selected = keys.some(k => this.state.data[k].active);
    const periodTexts = { 'live': 'Live', '1h': '1H', '1d': '1D', '1w': '1W', '2w': '2W' };

    // @TODO implement others on the backend
    delete periodTexts['1h'];
    delete periodTexts['1d'];
    delete periodTexts['1w'];
    delete periodTexts['2w'];

    return (
        <div id="page">
          <div id="header">
            <div className="row">
              <div id="sections" className="col-md-6">
                {keys.length > 0 &&  <div className="btn-group">
                   {keys.map(k => <button key={k} onClick={this.handleSectionClick(k)} className={classNames("btn", { "btn-primary": this.state.data[k].active, "btn-secondary": !this.state.data[k].active })}>{k}</button>)}
                </div> }
              </div>

              <div id="settings" className="col-md-6">
                <div className="btn-group">
                  <button type="button" className="btn btn-info dropdown-toggle" onClick={this.handlePeriodDropdownClick}>
                    {periodTexts[this.state.period]}
                  </button>
                  <div className={classNames("dropdown-menu dropdown-menu-right", { show: this.state.periodDropdownActive })}>
                    {Object.keys(periodTexts).map(p =>
                      <a key={p} className={classNames("dropdown-item", { "active": this.state.period === p })} href="#" onClick={this.handlePeriodClick(p)}>
                        {periodTexts[p]}
                      </a>
                    )}
                  </div>
                </div>
              </div>
            </div>
          </div>
          <div id="content">
            <div className="container-fluid">
              {keys.length < 1 && <div className="alert alert-info">Vapor hasn't received any data yet.</div>}
              {keys.length > 0 && !selected && <div className="alert alert-info">Select a category to get started.</div>}
              {keys.filter(k => this.state.data[k].active).map(k =>
                  <div>
                    {Object.keys(this.state.data[k].entries).map(n =>
                      <div className="card">
                        <div className="card-body">
                          <ResponsiveContainer width='100%' height={300}>
                            <AreaChart>
                              <XAxis
                                allowDuplicatedCategory={false}
                                allowDataOverflow={true}
                                dataKey = 'when'
                                domain = {[this.state.timestamp - TIME_LIMIT_MS, this.state.timestamp]}
                                name = 'Time'
                                tickFormatter = {(unixTime) => moment(unixTime).format('YYYY-MM-DD h:mm:ss a')}
                                tickCount={2}
                                tickLine={false}
                                axisLine={false}
                                stroke="#FFFFFF"
                                interval="preserveStartEnd"
                                type = 'number'
                              />
                              <YAxis />

                              <CartesianGrid strokeDasharray="3 3" />
                              <Tooltip labelFormatter={(label) => moment(label).format('YYYY-MM-DD h:mm:ss a')} />
                              <Legend verticalAlign="top" height={36}/>
                              <Area
                                isAnimationActive={false}
                                stroke="#0099CC"
                                strokeWidth={2}
                                fill="#006385"
                                dataKey="value"
                                data={this.state.data[k].entries[n]}
                                name={n}
                                key={n} />

                            </AreaChart>
                          </ResponsiveContainer>
                        </div>
                      </div>
                    )}
                  </div>
              )}
            </div>
          </div>
        </div>
    );
  }
}

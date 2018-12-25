import React from 'react';

import classNames from 'classnames';

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


export default class extends React.Component {
  constructor(props) {
    super(props);

    this.state = {
      active: [],
      available: {}
    };

    this.handleGaugeEntry = this.handleGaugeEntry.bind(this);
  }

  componentDidMount() {
    this.eventSource = new EventSource("/api/gauge-entries");

    this.eventSource.addEventListener('GaugeEntry', this.handleGaugeEntry);
  }

  componentWillUnmount() {
    this.eventSource.removeAllListeners();
    this.eventSource.close();
  }

  handleGaugeEntry(entry) {
    if (entry.data) {
        const parsed = JSON.parse(entry.data);

        if (parsed) {
          const components = parsed.name.split('.');

          if (components.length > 0) {
            const name = components.length > 1 ? components[0] + '.' : components[0];

            this.setState(state => {
                state.available[name] = true;
                return state;
            });
          }
        }
    }
  }

  handleSectionClick(name) {
    return (e) => {
        this.setState(state => {
            state.active[name] = !!!state.active[name];
            if (state.active[name] !== true) {
                delete state.active[name];
            }
            return state;
        });
    };
  }

  render() {
    const keys = Object.keys(this.state.available).sort();
    const active = Object.keys(this.state.active).sort();

    return (
        <div id="page">
          <div id="header">
            <div id="brand">Vapor</div>

            {keys.length > 0 &&  <div className="btn-group">
               {keys.map(k => <button key={k} onClick={this.handleSectionClick(k)} className={classNames("btn", { "btn-primary": !!this.state.active[k], "btn-secondary": !!!this.state.active[k] })}>{k}</button>)}
            </div> }
          </div>
          <div id="content">
            <div className="container-fluid">
              {keys.length < 1 && <div className="alert alert-info">Vapor hasn't received any data yet.</div>}
              {keys.length > 0 && active.length == 0 && <div className="alert alert-info">Select a category above to get started.</div>}
            </div>
            {active.map(k =>
                <div>
                    <div>{k}</div>
                    <hr/>
                </div>
            )}
          </div>
        </div>
    );
  }
}

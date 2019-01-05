import './index.scss';

import React from 'react';
import ReactDOM from 'react-dom';

import App from './components/App';

if (window.location.hash === undefined || !window.location.hash.startsWith('#')) {
  document.location.replace('#');
}

ReactDOM.render(
  <App />,
  document.getElementById('app')
);

import React from 'react';
import classNames from 'classnames';

export default class Dropdown extends React.Component {
  constructor(props) {
    super(props);

    this.state = {
      active: false
    };

    this.node = null;

    this.handleDropdownClick = this.handleDropdownClick.bind(this);
    this.handleMenuClick = this.handleMenuClick.bind(this);
    this.handleMousedown = this.handleMousedown.bind(this);
    this.setNode = this.setNode.bind(this);
  }

  componentDidMount() {
    document.addEventListener('mousedown', this.handleMousedown);
  }

  componentWillUnmount() {
    document.removeEventListener('mousedown', this.handleMousedown);
  }

  handleDropdownClick(e) {
    e.preventDefault();

    this.setState(s => { return { active: !s.active }; });
  }

  handleMenuClick(itemKey) {
    return (e) => {
      if (this.props.items[itemKey] !== undefined && this.props.items[itemKey].onClick !== undefined) {
        this.props.items[itemKey].onClick(e);
      } else {
        e.preventDefault();
      }

      this.setState({ active: false });
    };
  }

  handleMousedown(e) {
    if (this.node !== null && !this.node.contains(e.target) && e.which === 1) {
      this.setState({ active: false });
    }
  }

  setNode(node) {
    this.node = node;
  }

  render() {
    return (
      <div className="btn-group" ref={this.setNode}>
        <button type="button" className={`btn dropdown-toggle btn-${this.props.color}`} onClick={this.handleDropdownClick}>
          { this.props.name }
        </button>
        <div className={classNames("dropdown-menu", { "dropdown-menu-right": !!this.props.right, show: this.state.active })}>
          {Object.keys(this.props.items).map(itemKey =>
            <a key={itemKey} className={classNames("dropdown-item", { "active": !!this.props.items[itemKey].active })} href={this.props.items[itemKey].href} onClick={this.handleMenuClick(itemKey)}>
              {this.props.items[itemKey].name}
            </a>
          )}
        </div>
      </div>
    );
  }
}

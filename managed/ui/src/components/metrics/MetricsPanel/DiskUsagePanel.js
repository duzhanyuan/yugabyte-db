// Copyright (c) YugaByte, Inc.

import React, { Component, Fragment } from 'react';
import { Graph } from '../';
import {isNonEmptyArray} from 'utils/ObjectUtils';
import { YBResourceCount } from 'components/common/descriptors';
import './MetricsPanel.scss';

export default class DiskUsagePanel extends Component {
  static propTypes = {
  }

  render() {
    const { metric, isKubernetes } = this.props;
    const space = {
      free: undefined,
      used: undefined,
      size: undefined
    };
    if (isNonEmptyArray(metric.data)) {
      if (isKubernetes) {
        space.used = metric.data.find((item)=>item.name==="container_volume_stats").y[0];
        space.size = metric.data.find((item) => item.name==="container_volume_max_usage").y[0];
        space.free = space.size - space.used;
      } else {
        const freeArray = metric.data.find((item)=>item.name==="free").y;
        const sizeArray = metric.data.find((item)=>item.name==="size").y;
        const reducer = arr => arr.reduce((p, c) => parseFloat(p) + parseFloat(c), 0 ) / arr.length;
        space.free = reducer(freeArray);
        space.size = reducer(sizeArray);
        space.used = space.size - space.free;
      }
    }
    const value = space.size ? Math.round(space.used * 1000 / space.size) / 1000 : 0;
    return (
      <div className="metrics-padded-panel disk-usage-panel">
        { isNaN(space.size)
          ?
            <Fragment>
              <YBResourceCount size={"No Data"} />
              <span className="gray-text metric-subtitle">{"Data is unavailable"} </span>
              <Graph value={0} unit={"percent"} />
            </Fragment>
          :
            <Fragment>
              <YBResourceCount size={Math.round(space.used * 10)/10} unit="GB used" />
              {space.free &&
                <span className="gray-text metric-subtitle">
                  {Math.round(space.free)} GB free out of {Math.round(space.size)} GB
                </span>
              }
              <Graph value={value} unit={"percent"} />
            </Fragment>
        }
      </div>
    );
  }
}

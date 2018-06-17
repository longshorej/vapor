# Vapor

Vapor is an all-in-one metric collection and visualization system. Its main feature is simplicity -- no persistence, a simple protocol, and a limited conceptual model. Metrics are kept in memory and dropped after a configurable period of time.

It's currently very minimal and contains the minimal amount of features necessary to aid in monitoring a UDP-based audio system I'm developing for use at the Longshore household. More to come as needed.

## Getting Started

Run the daemon:

```bash
docker run --rm -p 6542:6542 -p 6542:6542/udp appalachian/vapord:latest
```

Send some data:

```bash
netcat -u localhost 6542
g.test.1234
```

View some data:

http://localhost:6542

## Terminology

### Gauge

A gauge is a recording of a distinct integer value that is timestamped on arrival.

```scala
case class Gauge(name: String, value: Long)
```

### Event

An event signifies the occurrence of some arbitrary event. These are collected and summed over a specified aggregation period by the UDP server.

```scala
case class Event(name: String, rollUpPeriod: Option[Long])
```

## Development

This project uses [sbt](https://www.scala-sbt.org/) for its main UDP server. Client libraries are provided for [Rust](https://www.rust-lang.org/en-US/) and [Akka](https://akka.io/).

## Releases

Clone a fresh copy from upstream and run `sbt release`. It will prompt for the next version number and take care of incrementing versions, creating tags, pushing the commits.

Docker images are created separately using the [dockerfiles](https://github.com/longshorej/dockerfiles) project.

## Author

Jason Longshore <hello@jasonlongshore.com>

## License

Copyright (C) 2018 Jason Longshore (https://www.jasonlongshore.com/).

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this project except in compliance with the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.


# vapord

vapord is an all-in-one metric collection and visualization system. Its main feature is simplicity -- no persistence, a simple protocol, and a limited conceptual model. Metrics are kept in memory and dropped after a configurable period of time.

It's currently very minimal and contains the minimal amount of features necessary to aid in monitoring a UDP-based audio system I'm developing for use at the Longshore household. More to come as needed.

## Getting Started

Run the daemon. You can find a list of versions [on DockerHub](https://hub.docker.com/r/appalachian/vapord/tags/):

```bash
docker run --rm -p 13542:13542 -p 13542:13542/udp appalachian/vapord:<version>
```

Send a gauge:

```bash
netcat -u localhost 13542
g/test/1234
```

Send an event:

```bash
netcat -u localhost
e/test
```

View some data:

http://localhost:13542

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

## Programmatic Use

[vapor-rust](https://github.com/appalachian-io/vapor-rust)

## Development

This project uses [sbt](https://www.scala-sbt.org/) for its daemon.

## Releases

This is currently harder than it needs to be.

1. Fresh clone from upstream
2. `cd vapor/backend`
3. `sbt release`
4. `git checkout v<version>`
5. `cd ../frontend; npm run build`
6. `cd ../backend; sbt assembly`
7. `cd ..`
7. `docker build -t appalachian/vapord:<version> .`
8. `docker push appalachian/vapord:<version>`

## Author

Jason Longshore <hello@jasonlongshore.com>

## License

Copyright (C) 2018 Jason Longshore (https://www.jasonlongshore.com/).

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this project except in compliance with the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.


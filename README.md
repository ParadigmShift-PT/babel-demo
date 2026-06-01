# babel-demo — a peer-to-peer chat built on Babel

A small, **software-only** demo of the [Babel](https://ieeexplore.ieee.org/document/9996836)
distributed-protocols framework: an **mIRC-style group chat** where every
participant is a peer. Start it on two machines on the same network and they
**find each other automatically** and start talking — no server, no broker, no
configuration.

It's a learning and prototyping on-ramp: if you've wanted to build distributed
systems in Java without hand-rolling sockets, threads, and failure handling,
this shows how Babel lets you compose clean, event-driven protocols instead. The
code is deliberately small and **heavily commented** so you can read it
end-to-end, run it, and start building your own protocols on top.

> **A ParadigmShift open demo.** Free for non-commercial use — see [License](#license).

---

## Contents

- [Quickstart](#quickstart)
- [Using the chat](#using-the-chat)
- [How it works](#how-it-works)
- [Configuration](#configuration)
- [Building from source](#building-from-source)
- [Running across machines & the contact fallback](#running-across-machines--the-contact-fallback)
- [Project layout](#project-layout)
- [Logging & troubleshooting](#logging--troubleshooting)
- [Distribution](#distribution)
- [Credits](#credits) · [License](#license)

---

## Quickstart

Requires **Java 17+**. Grab `babel-demo.jar` from the
[latest release](https://github.com/ParadigmShift-PT/babel-demo/releases/latest)
(or [build it](#building-from-source)), then run a node per terminal/machine:

```bash
java -jar babel-demo.jar nick=alice
java -jar babel-demo.jar nick=bob          # another terminal or machine
```

On the same LAN they discover each other automatically. On the **same machine**,
give each node a distinct port:

```bash
java -jar babel-demo.jar nick=alice babel.port=6001
java -jar babel-demo.jar nick=bob   babel.port=6002 contact=127.0.0.1:6001
```

Type a line to talk to everyone; `/help` lists the commands.

---

## Using the chat

```
*** babel-demo — connected as alice. Type /help for commands.
*** bob has joined the chat
[14:32] <bob> hi everyone
[14:32] <alice> hey bob
*** carol has joined the chat
[14:33] -> (carol) see you in 5      // a private message you sent
[14:33] <- (bob) sounds good          // a private message you received
*** bob has left the chat
```

| Input | Effect |
|---|---|
| `hello world` | send to **everyone** (the global channel) |
| `/msg <nick> <text>` | **private** message to one user |
| `/who` (or `/names`) | list who's currently in the chat |
| `/help` | show the commands |
| `/quit` | announce departure and exit |

The console uses [JLine](https://github.com/jline/jline3): your input line stays
fixed at the bottom while messages scroll in above it.

---

## How it works

babel-demo is three cooperating Babel protocols. Each is a `GenericProtocol` that
interacts with the others only through asynchronous **events** (requests,
replies, notifications, timers) — never by calling each other directly. That
decoupling is the heart of Babel, and the reason these pieces compose cleanly.

```
 ┌───────────────────────────────────────────────────────────────┐
 │  ChatApp (id 300)                                              │
 │   • reads your input, renders the console (JLine)             │
 │   • global message  → BroadcastRequest → FloodBroadcast       │
 │   • private message → sendMessage() directly to a peer        │
 │   • roster (Host → nickname) from a HELLO presence handshake  │
 │   • reacts to NeighbourUp/Down and DeliverNotification        │
 └───┬───────────────────────────────┬───────────────────────────┘
     │ BroadcastRequest               │ DeliverNotification
     ▼                                ▲
 ┌─────────────────────────────┐      │
 │  FloodBroadcast (id 200)    │──────┘   floods a message to every neighbour,
 │   • deliver once, forward   │          delivering it once to the app
 │     to all other neighbours │
 └───┬─────────────────────────┘
     │ NeighbourUp / NeighbourDown / ChannelCreated (notifications)
     ▼
 ┌─────────────────────────────────────────────────────────────┐
 │  GossipBasedFullMembership (id 100)                          │
 │   • owns the TCP channel; connects us to every other node    │
 │   • auto-discovery via Babel's DiscoverableProtocol          │
 │     (with a 'contact' fallback for when multicast is off)    │
 │   • announces neighbours coming and going                    │
 └─────────────────────────────────────────────────────────────┘
```

**Membership** keeps you connected to the other nodes. It bootstraps using
Babel's `DiscoverableProtocol`: nodes announce themselves by multicast and the
runtime introduces them, so no node needs a hard-coded address. Once connected,
periodic gossip ensures everyone learns about everyone (a *full* membership). It
emits `NeighbourUp` / `NeighbourDown` as peers come and go.

**Broadcast** is a flood: the first time you receive a message you deliver it
locally and forward it to every neighbour except the one you got it from
(duplicates are dropped by message id). It forwards only to the neighbours the
membership reports — so it's "neighbour-aware" — and it reuses the membership's
TCP channel rather than opening its own.

**ChatApp** ties it together. Global messages and graceful "leave" announcements
ride the broadcast; private messages and the presence handshake go point-to-point
(possible because full membership makes every user directly reachable). It keeps
a `Host → nickname` roster: when a neighbour appears it exchanges a `HELLO`
(carrying the nickname), and it drops people on `/quit` (a broadcast `LEAVE`) or
disconnect (`NeighbourDown`).

---

## Configuration

All keys can go in `babel_config.properties` or be passed as `key=value` launch
arguments (arguments win).

| Key | Default | Meaning |
|---|---|---|
| `nick` | *(required)* | your chat nickname |
| `babel.port` | `6000` | TCP port for this node — use a distinct one per node on a machine |
| `interface` | *(unset)* | network interface (e.g. `en0`, `eth0`) to bind/announce on; needed so LAN discovery advertises a reachable address |
| `contact` | `none` | bootstrap: `none` = first node · `host:port` = seed from that node · *(absent)* = wait for discovery |
| `protocol.membership.sampletime` | `2000` | ms between gossip samples |
| `protocol.membership.samplesize` | `6` | max peers per gossip sample |
| `protocol_metrics_interval` | `-1` | if `>0`, periodically log the membership view (ms) |

---

## Building from source

Requires **JDK 17** and **Maven 3.6+**.

```bash
mvn package
# → target/babel-demo.jar  (a runnable "fat" JAR with all dependencies)
java -jar target/babel-demo.jar nick=alice
```

You can build on any machine and copy the JAR to where you want to run it — only
running the chat needs to be on the network with the other nodes.

---

## Running across machines & the contact fallback

- **Same LAN (recommended):** just run each node with a `nick` (and an
  `interface=` if a node has several). Multicast discovery connects them.
- **Same machine:** multicast may not loop back between local JVMs — give each
  node a distinct `babel.port` and point later nodes at the first with
  `contact=127.0.0.1:<first-port>`.
- **Segmented networks / VPN / cloud:** where multicast is blocked, start one
  node as `contact=none` and seed the others with
  `contact=<that-node-host>:<port>`. Discovery and the contact fallback can be
  used together.

---

## Project layout

```
src/main/java/
├── Main.java                                       boot Babel, wire the 3 protocols
├── utils/InterfaceToIp.java                        resolve an interface name → IP
├── protocols/membership/
│   ├── full/GossipBasedFullMembership.java         membership (extends DiscoverableProtocol)
│   ├── full/messages/SampleMessage.java            gossip sample
│   ├── full/timers/{SampleTimer,InfoTimer}.java
│   └── common/notifications/{NeighbourUp,NeighbourDown,ChannelCreated}.java
├── protocols/broadcast/
│   ├── flood/FloodBroadcast.java                   neighbour-aware flood
│   ├── flood/messages/BroadcastMessage.java
│   └── common/{BroadcastRequest,DeliverNotification}.java
└── protocols/apps/chat/
    ├── ChatApp.java                                the interactive chat
    ├── messages/ChatDirectMessage.java             HELLO / private message
    ├── ChatPayload.java                            app framing inside a broadcast
    └── ui/Console.java                             JLine terminal
src/main/resources/
├── babel_config.properties                         defaults (see Configuration)
└── log4j2.xml                                       logs to a file, not the console
```

Protocol/event ids follow the Babel convention (protocols at 100-multiples,
events numbered from `protocol_id + 1`): membership `100`, broadcast `200`, chat
`300`.

---

## Logging & troubleshooting

Logs go to a **file**, not the console, so they never disturb the chat UI. Each
node writes `babel-demo-<port>.log`. Watch one with `tail -f babel-demo-6001.log`
to see membership converge and messages flow.

- **Nodes don't find each other on a LAN:** pass `interface=<your-nic>` so the
  announced address is reachable, or use the `contact=` fallback.
- **Two local nodes:** they must use different `babel.port` values.

---

## Distribution

This is a **runnable demo, not a library**, so it is *not* published to the
ParadigmShift Maven repository. The CI (`.github/workflows/ci.yml`) builds the fat
JAR on every push, and on a version tag (`vX.Y.Z`) attaches it to a **GitHub
Release**. To cut a release: `git tag v0.1.0 && git push origin v0.1.0`.

---

## Credits

Developed by **[ParadigmShift, Lda.](https://www.paradigmshift.pt)** on the
ParadigmShift fork of Babel-Swarm, and modelled in structure and style on the
upstream `babel-example` from the
[Computer Systems Group](https://novasys.di.fct.unl.pt) of
[NOVA LINCS](https://nova-lincs.di.fct.unl.pt) at NOVA FCT.

Babel is described in: P. Fouto, P. Á. Costa, N. Preguiça and J. Leitão,
"[Babel: A Framework for Developing Performant and Dependable Distributed
Protocols](https://ieeexplore.ieee.org/document/9996836)," *2022 41st
International Symposium on Reliable Distributed Systems (SRDS)*, Vienna, Austria,
2022, pp. 146–155, doi: 10.1109/SRDS55811.2022.00022.

## License

ParadigmShift Proprietary License — free for non-commercial use (research,
personal projects, evaluation); commercial use requires a written licence from
ParadigmShift. See [LICENSE](LICENSE) for the full terms. Contact
info@paradigmshift.pt.

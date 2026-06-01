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

On the same LAN they discover each other automatically. Each node picks its
network interface automatically (see [Choosing the interface](#choosing-the-network-interface-experimental));
if your machine has several real NICs it will ask you to pick one with
`babel.interface=<nic>`.

**Two nodes on the same machine** need a distinct `babel.port` *and* a distinct
`babel.discovery.unicast.port` (the per-node discovery socket, default `1026`,
can't be shared — see [Configuration](#configuration)):

```bash
java -jar babel-demo.jar nick=alice babel.discovery.unicast.port=1026
java -jar babel-demo.jar nick=bob   babel.port=6001 babel.discovery.unicast.port=1027
```

If two local nodes don't find each other (some OSes don't loop multicast back
between local processes), pin both to loopback and seed the second from the first:

```bash
java -jar babel-demo.jar nick=alice babel.address=127.0.0.1 babel.discovery.unicast.port=1026 membership.contact=none
java -jar babel-demo.jar nick=bob   babel.address=127.0.0.1 babel.port=6001 babel.discovery.unicast.port=1027 membership.contact=127.0.0.1:6000
```

Omit `nick=` and you'll simply be asked for a nickname at startup. Type a line to
talk to everyone; `/help` lists the commands.

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
 │   • reacts to NeighborUp/Down and BroadcastDelivery           │
 └───┬───────────────────────────────┬───────────────────────────┘
     │ BroadcastRequest               │ BroadcastDelivery
     ▼                                ▲
 ┌─────────────────────────────┐      │
 │  FloodBroadcast (id 200)    │──────┘   floods a message to every neighbour,
 │   • deliver once, forward   │          delivering it once to the app
 │     to all other neighbours │
 └───┬─────────────────────────┘
     │ NeighborUp / NeighborDown / ChannelAvailable (notifications)
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
runtime introduces them, so no node needs a hard-coded address. This only happens
because `babel_config.properties` sets `babel.discovery` to a discovery protocol —
without that key Babel loads no discovery and nodes connect only via the
`membership.contact` fallback. Once connected, periodic gossip ensures everyone
learns about everyone (a *full* membership). It emits the shared `NeighborUp` /
`NeighborDown` notifications as peers come and go.

The cross-protocol events (`BroadcastRequest`, `BroadcastDelivery`, `NeighborUp`,
`NeighborDown`, `ChannelAvailableNotification`) are the reusable abstractions from
the `babel-protocols-common` library — the same ones the production ParadigmShift
protocols use — not types invented here.

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
disconnect (`NeighborDown`).

---

## Configuration

All keys can go in `babel_config.properties` or be passed as `key=value` launch
arguments (arguments win).

| Key | Default | Meaning |
|---|---|---|
| `nick` | *(prompted)* | your chat nickname; if omitted, you're asked for one at startup (must be passed when there's no interactive terminal) |
| `babel.port` | `6000` | TCP port for this node — use a distinct one per node on a machine |
| `babel.interface` | *(unset)* | network interface (e.g. `en0`, `eth0`) to bind/announce on; if unset, it is auto-detected ([experimental](#choosing-the-network-interface-experimental)) |
| `babel.address` | *(auto-detected)* | bind/announce IP; overrides interface auto-detection. Use `127.0.0.1` to run several nodes on one disconnected machine. There is **no** loopback default — if no interface can be auto-detected you must set this or `babel.interface` |
| `babel.discovery` | `…discovery.MulticastDiscoveryProtocol` | discovery protocol class(es) (`;`-separated) enabling LAN auto-discovery; **required** for discovery to run at all — remove it and nodes connect only via `membership.contact` |
| `babel.discovery.unicast.port` | `1026` | per-node UDP port for the discovery unicast socket; **give each node on one machine a distinct value** or the second fails with `BindException: Address already in use` |
| `babel.discovery.multicast.port` | `1025` | shared multicast rendezvous port; keep it **identical** across nodes (it's reused safely per host) — varying it stops nodes from discovering each other |
| `membership.contact` | *(unset)* | bootstrap mode: *(absent)* = **joiner**, probe the LAN for peers (and reply to others) · `none` = **first node**, don't probe but still reply to others' probes · `host:port` = seed directly from that node |
| `membership.sampletime` | `2000` | ms between gossip samples |
| `membership.samplesize` | `6` | max peers per gossip sample |
| `membership.metrics.interval` | `-1` | if `>0`, periodically log the membership view (ms) |

Naming: process-wide bind parameters are namespaced `babel.*`; a protocol's own parameters are namespaced by the protocol (here, `membership.*`). The launcher's `nick` is the one bare, user-facing parameter.

### Choosing the network interface (experimental)

A node must bind a **reachable** address — not loopback, and not a virtual/VM
interface — so that the address it announces is one peers can actually connect to.
On startup babel-demo prints exactly what it chose, e.g.:

```
  network     : en0  →  192.168.20.158   (auto-detected sole interface en0)
  listen      : 192.168.20.158:6000  (TCP chat)
```

**Auto-detection is a best-effort heuristic — treat it as experimental.** With no
`babel.interface` / `babel.address` set, the node looks for interfaces that are up,
non-loopback, non-point-to-point, have a routable (non-link-local) IPv4, and whose
name is *not* a known bridge / VM / container / VPN adapter (`bridge*`, `vmenet*`,
`docker*`, `utun*`, …). Then:

- **exactly one** such interface → it's selected automatically (the common laptop case);
- **several** → it refuses to guess and lists them, so you pick one explicitly;
- **none** (only bridges/VPNs, or nothing) → it asks you to name one.

Because the bridge/VM exclusion is name-based, it can be wrong on unusual setups.
**Whenever in doubt, set `babel.interface=<nic>` (or `babel.address=<ip>`) explicitly** —
that always wins and skips the heuristic. The full interface inventory is written to
the node's log file (`babel-demo-<port>.log`) to help diagnose a bad pick.

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

By default every node is a **joiner**: it probes the LAN for peers and also replies
to others' probes, so two joiners find each other with no contact configured at all.

- **Same LAN (recommended):** just run each node with a `nick` (and
  `babel.interface=<nic>` if [auto-detection](#choosing-the-network-interface-experimental)
  can't decide). Multicast discovery connects them.
- **Same machine:** give each node a distinct `babel.port` **and** a distinct
  `babel.discovery.unicast.port` — the discovery unicast socket (default `1026`) is
  per-process with no port reuse, so two local nodes clash on it otherwise (the
  multicast rendezvous port `1025` is shared on purpose — leave it). If local
  multicast doesn't loop back, use the contact fallback below.
- **Segmented networks / VPN / cloud, or flaky local multicast:** where multicast is
  unavailable, start one node as `membership.contact=none` (it won't probe but still
  answers, and is reachable as a seed) and point the others at it with
  `membership.contact=<that-node-host>:<port>`.

---

## Project layout

```
src/main/java/
├── Main.java                                       boot Babel, wire the 3 protocols
├── utils/InterfaceToIp.java                        resolve the bind address (interface / auto-detect)
├── protocols/membership/
│   ├── full/GossipBasedFullMembership.java         membership (extends DiscoverableProtocol)
│   ├── full/messages/MembershipSampleMessage.java            gossip sample
│   └── full/timers/{SampleTimer,InfoTimer}.java
├── protocols/broadcast/
│   ├── flood/FloodBroadcast.java                   neighbour-aware flood
│   └── flood/messages/BroadcastMessage.java        its own wire message
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

- **Check the startup banner first** — it prints the chosen interface/IP, the
  discovery ports, and the bootstrap mode. Most discovery problems are visible there
  (e.g. it bound a VM bridge address, or every node is a `none` first node so nobody
  probes).
- **Nodes don't find each other on a LAN:** the [auto-detected](#choosing-the-network-interface-experimental)
  interface may be wrong (the banner shows it) — pass `babel.interface=<your-nic>`
  (or `babel.address=<ip>`) so the announced address is reachable, or use the
  `membership.contact=` fallback. Also make sure at least one node is a joiner
  (`membership.contact` **absent**); if every node uses `none`, none of them probe.
- **"Several … interfaces are usable — refusing to guess":** auto-detection found
  more than one real NIC. Pass `babel.interface=<nic>` (the message lists them).
- **"Could not auto-detect a reachable network interface" / "Only bridge / VM / VPN
  interfaces":** no plain physical NIC was found (offline, or only virtual adapters).
  Pass `babel.interface=<nic>` or `babel.address=<ip>` (e.g. `127.0.0.1`) explicitly.
- **Two local nodes:** use different `babel.port` **and** `babel.discovery.unicast.port`
  values (pin both to `babel.address=127.0.0.1` if you don't want them on the LAN).
- **`BindException: Address already in use` at startup (in `Babel.start`):** another
  discovery-enabled node on this machine already holds the discovery unicast port
  (`1026`). Give this node a distinct `babel.discovery.unicast.port` (e.g. `1027`).
  Leave `babel.discovery.multicast.port` alone — that one is meant to be shared.

---

## Distribution

This is a **runnable demo, not a library**, so it is *not* published to the
ParadigmShift Maven repository. The CI (`.github/workflows/ci.yml`) builds the fat
JAR on every push, and on a version tag (`vX.Y.Z`) attaches it to a **GitHub
Release**. To cut a release: `git tag v0.1.1 && git push origin v0.1.1`.

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

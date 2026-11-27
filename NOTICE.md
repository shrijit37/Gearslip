# License

Gearslip is licensed under the GNU Affero General Public License v3.0. The full text is in `LICENSE`.

You may use, run, and modify Gearslip freely. If you distribute a modified version, or let others use one over a network, you must publish your changes under the same license. The obligation follows the code even when you hand out no binary, which suits software that runs as a background service.

## Commercial licensing

If the AGPL's terms don't work for you, Twink Software LLC, which owns Gearslip, sells a separate commercial license. The usual case is a company that wants to use Gearslip privately without publishing its changes. Terms, including any limits on modification, are set case by case. Contact us on Matrix; the address is on the [Seb3thehacker GitHub profile](https://github.com/Seb3thehacker).

## Protocol history and app channels

Gearslip implements the Android Auto Protocol: USB and wireless connection, TLS and certificates, service discovery and framing, and the audio, media, call and notification channels. It hosts templated car apps through the Android for Cars App Library. The protocol work began in [twinksoftware/Gearslip](https://github.com/twinksoftware/Gearslip).

Gearslip does not stream music itself. Media, calls, notifications and messages are carried over the protocol to and from apps installed on the phone. The first-party InnerTube catalog/search client, metadata cleaner and bounded-range `ChunkedDataSource` were ported in for an earlier player and later removed; the attribution history below is kept for accuracy.

## Third-party code and attribution history

### Vela-style keyless route traffic (GPL-3.0) — removed

`gearslip/.../traffic/VelaTrafficSource.kt` followed the approach documented by [Vela Maps](https://github.com/PimpinPumpkin/Vela) (GPL-3.0, © PimpinPumpkin): a per-install logged-out browser session, a keyless `GET /maps/preview/directions`, E7 delta-encoded geometry, and congestion spans of the form `[level, startMeters, lengthMeters]` (1 = moderate, 2 = heavy; free-flow gaps are omitted). The captured `pb` request template was reproduced from Vela's `DirectionsPb.kt`; the client and parser were otherwise reimplemented for Gearslip. That source has since been removed, so no Vela-derived code remains in the tree. Gearslip is AGPL-3.0, which is GPL-3.0-compatible, so the adapted code was under AGPL-3.0 while it existed. No API key or shared credential was used.

### Echo Music (GPL-3.0) — ported, then removed

[Echo Music](https://github.com/EchoMusicApp/Echo-Music) — GPL-3.0. Its InnerTube catalog/search client, metadata text cleaner and bounded-range `ChunkedDataSource` were ported into Gearslip (into the `catalog` module and `gearslip/.../music/playback`, with the original source headers retained in the copied files); upstream also credits Metrolist, ViMusic, ArchiveTune, SimpMusic and BravePipe. That code has since been removed: Gearslip now provides the protocol channels and hosts external car apps, and no Echo-derived source remains in the tree. Gearslip is AGPL-3.0, which is GPL-3.0-compatible, so the ported code was under AGPL-3.0 while it existed.

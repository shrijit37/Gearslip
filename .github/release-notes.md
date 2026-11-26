Gearslip 0.5.0: Android Auto Protocol core and in-app updates.

## Protocol
- USB and wireless sessions with TLS, certificates, service discovery, framing and the audio, media, call and notification channels.
- Installed templated car apps run over the session through the Android for Cars App Library.
- The car video channel carries a placeholder test-card frame. No headunit UI is bundled.

## Updates
- Settings > Check for updates finds a new build, downloads the APK made for your phone's processor, checks its SHA-256, reads its version code and installs it.
- Gearslip ships one APK per processor and a universal fallback, so an update is about 20 MB instead of 35 MB.
- An install you start during a drive waits until the car lets go. The first install asks you to allow Gearslip to install apps.

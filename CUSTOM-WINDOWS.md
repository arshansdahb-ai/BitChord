# BitChord Custom for Windows - 1.8.1-custom14

Personal, unofficial build. This is not an official BitChord release and does not have complete Android feature parity.

## Source

- Upstream: https://github.com/kushagrasinghx/BitChord
- Desktop base: 0a3931e5e6033859f341571365f241d9bab18069 (last source tree before desktop removal).
- Shared playback/data module updated to upstream tag v1.8.1.
- Original GPLv3 license and contributor notices retained.

## Changes from the released Windows 1.8 beta

The custom14 home screen adapts the iBaila reference composition: a recent-listening rail, a centered clock and clickable cover flow, and the existing BitChord discovery feed below. Selecting a track starts it in BitChord's queue. While a track plays, available motion artwork can fill the desktop backdrop; pausing returns to the still artwork. The window edge responds to the audio level and cover palette, with a setting to disable it. The reduced-motion preference stops the moving backdrop. Account validation completes before the home feed loads; a stale session falls back to guest playback instead of showing a startup requirement error. Library, queue, lyrics, DJ, mixing, settings, playback and phone features remain in their existing destinations.

The newer upstream desktop source brings its Apple Music-style player redesign, expanded Spotify library and playlist importing, in-app WebView sign-in, queue history, equalizer peak limiter, Automix transition effects, measured Discord audio quality, and subsequent desktop fixes.

This custom build adds:

- **Song info** in the song overflow menu, using the v1.8.1 metadata parser. Shows available album/release details and the current upload's view/like/comment counts. Missing provider metadata is omitted.
- **Preferred song language** in the Spotify playlist import dialog. This is a matching preference, not a guarantee of language identification.
- v1.8.1 shared stream-resolution/uploaded-track fixes, metadata telemetry changes, KuGou instrumental-notice filtering, and shared queue improvements.
- Separate custom application name, settings, cache, downloads, logs and analysis directory.
- Custom builds do not prompt to replace themselves with official installers or send install identifiers to the official live-app counter.

## Mix Studio upgrade

Open **Mix Studio** from the top More menu, or use **Mix this playlist** from an album/playlist menu.

- Five mix presets: Smart blend, Club set, Smooth radio, Focus flow and Album fidelity.
- Adaptive, 8-bar or 16-bar Automix phrases, adjustable fallback fades, mix-on-skip and transition-effect depth.
- Equal-power, linear and soft manual crossfade curves.
- Save a mix profile for a particular playlist; it is applied when playback starts from that playlist.
- Edit the fade length and curve between two specific tracks. A saved pair uses its manual fade instead of neural beat matching. Pair edits apply to that pair wherever it plays on this computer.
- Smart queue sequencing based on available cached tempo and key, with artist spacing and smooth/build/wind-down direction choices.
- Optional batch analysis of up to twelve tracks using the bundled neural beat analyzer. Each track has a 90-second wait limit; cancel by pressing Stop or closing Mix Studio.
- Discovery in Mix Studio: eight mood presets, artist/genre/theme search, recent-song exclusion, an editable preview, Play mix, Queue next and Save playlist. This quick-discovery tool is separate from the conversational DJ tab. Neural beat analysis powers the mixing; unknown tracks are not assigned invented BPM/key values.
- Automatic motion art plus custom per-track HTTPS video links. Switch away and reopen the player to refresh a newly saved custom clip.
- Living still artwork: gentle camera drift while playing where no video has rendered. Reduce motion and Animated cover art control this behavior.
- Liquid-glass edge highlights over the existing translucent chrome; Mica/Acrylic controls and whole-window artwork atmosphere.
- Source quality and 16-bit/32-bit float processing controls, a fidelity preset, stereo widening and silence skipping.
- Slow RMS level matching with adjustable -24 to -12 dBFS target, plus a stereo-linked final-bus peak limiter. This is not LUFS normalization, mastering or bit-perfect output. Increasing precision does not turn lossy audio into lossless audio.
- Playlist mixing is suspended in Listen Together sessions to preserve synchronized playback.

Mix settings, playlist profiles, track-pair overrides and motion artwork links are saved only in this custom app's local preferences. Saving a DJ playlist creates a local playlist, without editing your streaming accounts.

## Standalone website and four-digit device connection (custom10)

Open https://bitchord.retrshot.chatgpt.site/ to use the public web player immediately. No PC invitation is needed for independent search, available full-track YouTube playback, local files, playlists, favorites, queues, lyrics and browser DJ mixes. Online tracks use the visible official YouTube player, so adverts, embedded-playback availability, quality and mobile/background restrictions apply. The browser DJ uses recent listening plus typed directions and browser speech; the PC local model and Piper voice remain PC features. Native automixing, equalizer processing, integrations, PC downloads and desktop-only controls are not reproduced by standalone YouTube playback. Favorites, playlists, preferences and listening history are saved in that browser, rather than automatically synced across devices.

To play on a PC: in BitChord choose **More > Phone player & Jam > Start phone session** with HTTPS enabled, then **Show connection code**. On the phone website choose **Connect to a device**, enter the PC's four digits, and request connection. Approve the named request on the PC. The phone then opens the PC companion automatically with PC playback selected. Codes last two minutes and cannot grant control without receiver approval. The existing QR/invitation flow remains as an optional compatibility method.

To play on another browser: choose **Connect to a device > Show this device's code** on the receiver. Enter those digits on the controlling website and approve on the receiving browser. Leave that receiver open. Online tracks and queues can be controlled; local files remain on their selecting device. Stop receiving disconnects controllers. Browser connections expire after the receiver stops responding. Pairing secrets are kept in memory and the service encrypts pending invitation destinations in its database. Short codes are rate limited, expire quickly and are consumed after approval and claim.

The Windows companion uses an outbound HTTPS relay and leaves Windows Firewall unchanged. No administrator access, router forwarding or background service installation is needed. The original BitChord logo remains on the web player, favicon and Home Screen icon.

## iPhone web player and no-admin HTTPS (custom8)

Open **More > Phone player & Jam**. Leave **No-admin HTTPS relay** on and choose **Start phone session**. Scan the controller QR code with iPhone Camera, open it in Safari, and connect. For a Home Screen shortcut, choose Safari Share > Add to Home Screen. Choose **This iPhone / this device** to hear music on your phone or **This PC** to control the Windows player. These are separate playback sessions; choosing a target does not silently stop someone else's session.

The phone player includes:

- Home and recent listening, song/album/artist/playlist search, album/playlist browsing, PC favorites/downloads/local files, and phone or PC playlists.
- Full screen now playing, independent phone queue, seek, shuffle, repeat, speed, timed lyrics when available, song radio, favorites, playlist add/remove/rename/delete, and a real audio-file download link for Safari Files.
- The DJ tab accepts typed requests or iPhone keyboard dictation. It uses the existing small local model and PC listening history, returns a set, and streams a locally rendered neural introduction to the phone. PC-targeted sets use the PC DJ. This is not Spotify's proprietary DJ or voice.
- Supported PC controls and settings: playback/queue/volume/output, streaming quality, speed/repeat/shuffle, AutoPlay, Mix Studio presets, phrase length/crossfade/effects/level matching/target level, spatial processing, skip silence, artwork/glass/lyrics appearance, and DJ narration. Phone settings are separate. PC processing does not affect the encoded audio sent to the iPhone.
- Guest invitations allow friends to search, play on their own phones, and add music to the shared PC Jam queue. Guests cannot read the host library or change PC settings. PC playback controls require a controller invitation or the host's guest-control switch. Up to 16 paired devices and four simultaneous phone audio streams are supported.

**Headphones and speakers:** Connect or pair headphones through iOS. The phone Audio routes button opens Safari's AirPlay picker when supported; Bluetooth routes use iPhone Control Center. The PC output list shows Windows audio endpoints, including connected Bluetooth devices. This does not independently discover or pair arbitrary nearby Bluetooth devices, Chromecast, Spotify Connect, or every network speaker. Compatible AirPlay devices use iOS's discovery and routing.

**No administrator rights:** The bundled, pinned official cloudflared portable connector opens an outbound HTTPS tunnel to the loopback-only server. No firewall rule, service, administrator prompt or router forwarding is created. Cloudflare carries page content, metadata, and streamed audio, as approved. The temporary trycloudflare.com address changes on every start, needs internet and has no uptime guarantee. Keep the PC and BitChord open. A stable permanent app address would require additional hosting/domain configuration. The local HTTP mode remains optional for networks that already permit it; the app does not attempt to grant a firewall exception.

**Access and cleanup:** Controller invitations grant library, playback and supported settings access; share them only with trusted people. Guest links grant reduced music access. Pairing uses random 256-bit URL-fragment invitations and eight-hour HttpOnly SameSite cookies (Secure for HTTPS). Song streams use random short-lived capabilities bound to a paired session. Ending the session or closing BitChord stops its server and owned relay, revokes all invitations/session cookies/audio links and cancels ongoing streams. Host/Origin checks, bounded requests, rate limits and registered opaque song IDs guard API operations. No arbitrary path, URL proxy, remote shell or account-token endpoint is exposed. Known library files are streamed only by registered song identity.

**Platform differences:** This is an iPhone Safari/Home Screen web app, not a signed native App Store app, Apple Music client, or exact desktop replica. Windows account sign-in/import/integrations, full equalizer/beat mixing, pair-specific editing and native desktop management remain on the PC. The phone has no guaranteed offline playback, independent operation with the PC closed, universal Bluetooth/network device discovery, or guaranteed Safari background continuation. Unsupported source/file formats report an error; online phone playback requests AAC/MP4 suitable for Safari. Requested quality depends on the original provider and is not newly created lossless audio. Media Session hooks support lock-screen controls where the browser allows them. Physical iPhone/headphone/AirPlay playback still needs validation on the user's devices.

Connector: cloudflared 2026.10.5 (55.3 MB), official release SHA-256 db4e4c413612a0916f3e1679d3caaf376f94911952d163028fb726fb2e0516a9, license and provenance in app/resources/relay. The connector runs only during an HTTPS phone session. Documentation: https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/ ; https://developer.apple.com/documentation/webkitjs/adding_an_airplay_button_to_your_safari_media_controls

## Natural local voice (custom6)

The Windows package now includes Piper and the American English Joe neural voice. Speech is generated locally, with no cloud voice service, API key, or speech subscription. The voice model is 63.2 MB; the complete runtime and voice package adds approximately 102 MB on disk. Piper runs only while generating an announcement, with CPU affinity limited to two available logical processors where Windows permits it, then exits. Audio is played by a short-lived Windows helper. Stop voice, End DJ, and quitting the app terminate the owned helper and its synthesis child.

Joe is a synthetic voice, not a human recording or a Spotify DJ voice clone. The model card and CC0 dataset license are included with the voice files. Legacy Windows speech is retained only as a fallback when the packaged neural files are absent. Microphone recognition continues to use the installed Windows recognizer.

References: https://github.com/rhasspy/piper/releases/tag/2023.11.14-2 ; https://huggingface.co/rhasspy/piper-voices/tree/main/en/en_US/joe/medium

## Window close fix (custom5)

Closing the window now quits the app and stops music and DJ speech. Existing custom installations are switched to this behavior once on upgrade. Keep playing when closed is off by default; minimizing remains available. Audio shutdown waits for the playback thread to close its output before the process exits.

## DJ interaction upgrade (custom4)

- A dedicated playback view with the publicly published Spotify DJ ring artwork, green Play control, Ask DJ, and Switch up the vibe.
- Ask DJ opens a request view with three suggestions grounded in this app's listening history, a text field, push-to-talk microphone, and a conversation view.
- Start my DJ and Switch up the vibe choose actual recent-listening seeds without loading a language model. Changing the vibe rotates between top recent artists; streamed recommendations still come from BitChord's music provider.
- The small model selects music search terms only. Spoken acknowledgements use bounded scripts so model-generated artist facts cannot leak into narration. Spoken set introductions name the first song actually selected. Commentary occurs after four new tracks, with varied short scripts; pause/resume and repeat-one do not count as new tracks.
- Windows' installed Microsoft David voice is preferred, with a slightly faster speaking rate. This is not DJ X's voice, a human voice clone, or Spotify's speech engine.
- This is a Windows adaptation of publicly documented DJ interactions. It is not an exact replica of Spotify's appearance, voice, recommendations, editorial knowledge, multilingual support, or connected-device behavior. The rest of the app retains the Apple-inspired layout from custom3.

Visual reference artwork: https://storage.googleapis.com/pr-newsroom-wp/1/2025/10/DJ-Header-2-1920x733.png (Spotify Newsroom). Original artwork belongs to Spotify; included in this personal unofficial build as the user's requested visual reference. No affiliation or endorsement.
Interaction references: https://newsroom.spotify.com/2025-10-15/dj-spanish-text-requests-update/ ; https://support.spotify.com/ie/article/dj/

## Talking DJ and iPhone-style desktop layout

The **DJ** tab is a dedicated local conversational DJ. It accepts typed music requests or up to twelve seconds of push-to-talk speech, builds a personal queue using recent listening in this app, speaks short introductions, and adds commentary after each group of four tracks during an active DJ session. Pause, resume, next and current-track questions use local commands without loading the model. Turn Voice off or End DJ to stop narration.

- Language model: `qwen3:0.6b` through an existing local Ollama installation. The model download is approximately 523 MB and is separate from this application archive.
- The model is unloaded after every reply (`keep_alive: 0`). Context is bounded at 2048 tokens, generation at 128 tokens, and CPU threads at two; Ollama may use the GPU when available.
- The app can start Ollama's local server on demand if its standard per-user installation is present. An already-running Ollama service is reused. No cloud AI key or subscription is used.
- Voice input uses the PC's installed Windows System.Speech recognizer; output uses the bundled neural voice. English recognition is preferred. The microphone is opened only after pressing Talk to DJ, with a twelve-second limit; music is muted during capture. No recording is saved.
- Music is reduced while DJ speech plays, then restored. The voice helper exits after each operation.
- Conversation stays in memory for the current app session. Recent-listening context is sent only to the local loopback model endpoint. Music search and streaming still require internet access.
- The lightweight model can misunderstand requests or offer weak recommendations. This does not reproduce Spotify's proprietary recommendation model or its DJ X voice. The app uses a local neural voice, not a clone of a Spotify presenter.

The visual update adapts Apple's public iPhone Music design to Windows: large headings, a red accent, floating glass Home/New/DJ/Library/Search navigation, a mini-player above the tabs, artwork-led album pages, and adjustable glass tint. Windows window controls remain native. History, Downloads, Local Music, Mix Studio and Settings are in the top More menu. This is an unofficial desktop adaptation, not a pixel-identical iPhone app or an Apple Music client.

Public references: https://www.apple.com/apple-music/ ; https://www.apple.com/os/ios/ ; https://support.spotify.com/ie/article/dj/ ; https://ollama.com/library/qwen3:0.6b

## Known differences and limits

- Android-only hardware integrations, Wear OS, Android TV, Android widgets and Chromecast behavior are not ported.
- This build does not add Android 1.8.1's artist signatures, weekly Replay mode, bounded Android queue persistence or persistent offline audio caching. Desktop downloads remain available.
- Google/Spotify/Discord sign-in must be completed by the owner. Account features cannot be validated without signing in.
- Last.fm needs your own API credentials in its settings. Listen Together needs a server configured in its settings. No upstream private build secrets are included.
- Addon sources must be configured by the owner. Provider services can change or stop working independently of the app.
- Native analyzer and media-control libraries are compiled locally; this build is unsigned.

## Storage and removal

Application files are installed per user under `%LOCALAPPDATA%\Programs\BitChordCustom`.
The app's data is under `%LOCALAPPDATA%\BitChordCustom`, with saved downloads in its `Downloads` folder.
Settings use Java's Windows preferences store, node `com.music.bitchord.desktop.custom`; listening stats use the `.stats` node.
To remove: quit from the tray, remove the custom application directory and its shortcuts. Keep the data folder if you want to preserve downloaded music and caches. Removing the application does not remove music automatically.

## Rebuilding

For the bundled neural voice, first run `python desktopApp/native/voice/fetch_voice.py` and `python desktopApp/native/relay/fetch_relay.py` (Python is needed only to download build assets, not to run the installed app). Then use JDK 21, MinGW-w64 g++, CMake and Ninja on PATH. Set JAVA_HOME to the JDK 21 directory.

```
gradlew.bat :shared:jvmTest :desktopUi:jvmTest :desktopApp:test :desktopApp:createDistributable --max-workers=4
```

The runnable application is created beneath `desktopApp/build/compose/binaries/main/app` and bundles a Java runtime. The desktop native build needs the compiler tools listed above to retain Automix and Windows frame/media integration.

# Remote Video Player — User Guide

🇷🇺 [Русская версия](GUIDE.ru.md)

Remote Video Player is an Android player for videos stored on a PC or NAS on your home network. It supports SMB (Windows shares, Samba, Synology/QNAP), WebDAV, and HTTP (nginx/Apache file listings or direct links). Button and setting names below match the app's English interface.

## Contents
1. [Installation](#1-installation)
2. [First launch](#2-first-launch)
3. [Adding a server: SMB](#3-adding-a-server-smb)
4. [Adding a server: WebDAV](#4-adding-a-server-webdav)
5. [Adding a server: HTTP](#5-adding-a-server-http)
6. [Test connection and error messages](#6-test-connection-and-error-messages)
7. [Posters & titles (TMDB)](#7-posters--titles-tmdb)
8. [Settings](#8-settings)
9. [Player](#9-player)
10. [Favourites, Continue watching, search](#10-favourites-continue-watching-search)
11. [Export and import servers](#11-export-and-import-servers)
12. [Troubleshooting (FAQ)](#12-troubleshooting-faq)
13. [Privacy](#13-privacy)

---

## 1. Installation

You need Android 8.0 or newer.

1. Download `AndroidRemoteVideoPlayer-<version>-release.apk` from [Releases](https://github.com/assassins377/AndroidRemoteVideoPlayer/releases).
2. Open the file. Android will ask you to allow installs from unknown sources for the app you opened the APK with (your browser or file manager). Tap **Settings**, enable **Allow from this source**, then go back. Wording varies by phone maker.
3. Tap **Install**. If Play Protect warns about an unknown developer, choose "Install anyway".

To update, install the new APK over the old one. Your servers and settings are kept, because all releases are signed with the same key.

## 2. First launch

The main screen is **Servers**. When no servers exist yet, it says: "No servers. Tap + to add SMB, HTTP or WebDAV."

- **+** (bottom-right button) adds a server.
- **★** (top) opens **Favourites**.
- **⚙** (top) opens **Settings**.
- Tap a server to browse it. The pencil icon (**Edit**) opens the server's settings.

The app language follows the system. You can change it in **Settings → Interface → App language**.

## 3. Adding a server: SMB

Tap **+**. The **New server** screen opens. Select the **SMB** type at the top and fill in:

| Field | What to enter |
|---|---|
| **Name** | Any label, e.g. "Living-room NAS" |
| **Host (192.168.1.10)** | IP address or network name of the PC/NAS, without `smb://` or slashes. The standard port 445 is used |
| **Shared folder (share)** | Share name, e.g. `video`. Required |
| **User (empty = guest)** | Server account. Leave it empty for guest access |
| **Password** | The account password |
| **Domain (optional)** | Windows domain or workgroup. Usually left empty |

Once the host is filled in, **Find shares on server** asks the server for its share list. Pick a share from the list. Possible results:
- "No shares found — enter manually": the server returned no shares;
- "Could not get the list: … . Enter manually.": the request failed.

In either case, type the share name yourself.

Tap **Test connection**, then **Save**. **Save** becomes active once the host and share are filled in.

### Finding the IP address
- **Windows**: `Win+R` → `cmd` → `ipconfig`. Look for "IPv4 Address".
- **macOS/Linux**: `ip addr`, or System Settings → Network.
- **Synology**: DSM → Control Panel → Network → Network Interface, or find.synology.com.
- **QNAP**: Qfinder Pro, or Control Panel → Network & Virtual Switch.
- **Router**: the list of connected devices (DHCP clients).

To keep the IP from changing, reserve it for the device in your router's DHCP settings.

### Windows
1. Right-click the folder → **Properties → Sharing → Advanced Sharing** → "Share this folder". Note the share name.
2. Under **Permissions** and the **Security** tab, give your user "Read".
3. For a Microsoft account, the username is your e-mail and the password is your Microsoft account password, not your PIN.
4. The network profile must be **Private**, otherwise the firewall blocks SMB.

### Samba (Linux)
Example section in `/etc/samba/smb.conf`:
```ini
[video]
   path = /srv/video
   read only = yes
   valid users = alice
```
Set a password with `sudo smbpasswd -a alice` and restart `smbd`. The app speaks SMB2/SMB3. Legacy SMB1 is not supported, so make sure you don't have `server max protocol = NT1`.

### Synology (DSM)
1. **Control Panel → File Services → SMB**: enable the SMB service. Under **Advanced Settings**, set the minimum protocol to **SMB2** (or SMB2 and Large MTU) and the maximum to **SMB3**.
2. **Control Panel → User**: create a user or use an existing one.
3. **Shared Folder → Edit → Permissions**: give the user Read only or Read/Write.
4. In the app, put the share name (e.g. `video`) into **Shared folder (share)**, without `/volume1`.

### QNAP (QTS)
1. **Control Panel → Network & File Services → Win/Mac/NFS/WebDAV → Microsoft Networking**: enable file service for Microsoft networking. Under **Advanced Options**, set the highest SMB version to SMB3 and the lowest to SMB2.
2. **Privilege → Shared Folders → Edit Shared Folder Permission**: give the user read access.

### SMB dialect and encryption
These are global for all SMB servers. They live in **Settings → Network & buffer**:
- **SMB dialect**: **Auto** (tries SMB 3.1.1 first, then older versions down to SMB 2.0.2), **SMB2**, or **SMB3**. Forcing a version helps with old or picky NAS units.
- **SMB3 encryption**: turn it on if the server requires encryption, for example Synology's "Force SMB3 encryption". Encryption costs some phone CPU.

## 4. Adding a server: WebDAV

Select the **WEBDAV** type and fill in:
- **Name**;
- **URL (http://...)**: the full address of the WebDAV folder (formats below);
- **User (empty = guest)** and **Password**.

The app picks Basic or Digest authentication automatically.

| Server | URL format |
|---|---|
| Synology, **WebDAV Server** package | `http://192.168.1.10:5005/video` or `https://192.168.1.10:5006/video` |
| QNAP (WebDAV under "Win/Mac/NFS/WebDAV") | `http://192.168.1.20:8080/video` (HTTPS: `https://…:443/video`). The port is shown in QTS settings |
| Nextcloud | `https://cloud.example.com/remote.php/dav/files/USERNAME/Videos` |
| Apache/nginx/wsgidav | The folder you configured, e.g. `http://host:8081/dav` |

**Synology.** Install **WebDAV Server** from Package Center. Enable HTTP (5005) and/or HTTPS (5006). The user needs permission on the shared folder. If DSM has a separate WebDAV application permission, it must be allowed too.

**QNAP.** **Control Panel → Win/Mac/NFS/WebDAV → WebDAV**: enable WebDAV and choose either shared-folder permissions or WebDAV permissions.

**Nextcloud.** If your account uses two-factor authentication, create an app password under **Settings → Security → App passwords**.

### Self-signed HTTPS certificate
If the server's certificate isn't trusted by Android, a card appears after **Test connection** (or when you open a folder). It shows:
- "The server uses a self-signed certificate the system does not trust." (or "The server uses a certificate the system does not trust.");
- **Issued to**, **Issued by**, **Valid**, and the **SHA-256** fingerprint;
- "Compare the fingerprint with your server (NAS) settings before trusting it."

Compare the SHA-256 with the fingerprint in your NAS settings or with `openssl x509 -noout -fingerprint -sha256 -in cert.pem`. If it matches, tap **Trust this certificate**.

Once trusted, the server's settings show "Trusted certificate (SHA-256): XX:XX:…". **Forget certificate** removes the trust. Changes in this part take effect after "Save".

**Certificate change.** If the server's certificate changes and no longer matches the saved one, the app warns: "The certificate changed! If you did not renew it on the server, do not trust it." The button on this card is **Trust the new certificate**. Tap it only if you renewed the certificate yourself (for example, the NAS reissued it). Otherwise the server may be spoofed.

Trust is stored per server and applies only to that server.

## 5. Adding a server: HTTP

Select the **HTTP** type. The **URL (http://...)** field can be:
- **A file listing (autoindex)**, e.g. `http://192.168.1.10:8080/video/`. The app parses nginx `autoindex on;` and Apache `Options +Indexes` pages and shows folders, videos, and subtitle files.
- **A direct video link**, e.g. `http://host/movies/Film.mkv`. The browser then shows that single file.

If the site is password-protected (Basic or Digest), fill in **User** and **Password**. HTTPS with a self-signed certificate works the same way as for WebDAV (see above).

nginx example:
```nginx
location /video/ {
    alias /srv/video/;
    autoindex on;
}
```
Apache example:
```apache
<Directory "/srv/video">
    Options +Indexes
    Require all granted
</Directory>
```

## 6. Test connection and error messages

**Test connection** shows either "Connection successful" or "Error: <message>". The same messages appear in the folder browser together with a **Retry** button.

| Message | Meaning and fix |
|---|---|
| Wrong username or password | Bad credentials. Fix them in the server settings; the browser also shows "Change the username/password in server settings." |
| Access denied to folder or file | The user logged in but has no rights to the folder. Grant read access on the server |
| Folder, file or share not found | Wrong share name or path (SMB), or HTTP 404/410. Check the name, or use "Find shares on server" |
| Server not found (check the address) | The host name can't be resolved. Use the IP address instead |
| Server unreachable (off or another network) | No route: the server is off, the phone is on another network or mobile data, or a firewall blocks the port |
| Server response timed out | The server didn't answer in time: a sleeping NAS or weak Wi-Fi. Raise "Connection timeout" |
| Network error, connection lost | The connection dropped. Check Wi-Fi |
| Server certificate is not trusted (self-signed or unknown authority) | Self-signed HTTPS. Trust it via the card (section 4) |
| Warning: the server certificate changed and does not match the saved one. The server may be spoofed | The certificate differs from the saved one. See "Certificate change" in section 4 |
| Certificate was issued for a different server name | The certificate was issued for another name. Connect using the name in the certificate |
| Not a file listing page (…) | The HTTP URL didn't return an HTML file listing. Enable autoindex or use a direct video link |
| Not a WebDAV server (HTTP …) | The URL doesn't respond as WebDAV. Check the URL (Nextcloud needs `/remote.php/dav/files/…`) and that WebDAV is enabled |
| Unknown error | Something else. Tap **Retry** and check your settings |
| Server not found | The server was deleted (for example, when opening an old link) |
| Playback error: … | A player error. Buttons: **Retry** and **Back** |

You may sometimes see "… Open the folder again to check the certificate." in the browser. Go back and reopen the folder, and the certificate card will appear.

## 7. Posters & titles (TMDB)

Instead of raw file names, the app can show clean titles such as "The Matrix (1999)" with a line like "1080p · 4.2 GB". If you connect The Movie Database (TMDB), you also get posters and localized titles.

1. Sign up at [themoviedb.org](https://www.themoviedb.org/).
2. Go to your profile → **Settings → API** and create a key (Developer). Either the **API Key (v3)** or the **API Read Access Token (v4)** works.
3. In the app, open **Settings → Posters & titles (TMDB)**. Paste the key into **TMDB API key (v3) or read token (v4)** and tap **Save key**. The status line changes to "Key saved (encrypted)".
4. Turn on **Load posters and titles**.

Titles use the app language. **Remove key** deletes the stored key.

Related errors: "TMDB: invalid API key" and "TMDB key is not set".

**Wrong match.** Long-press a file to open **Details**. It has a **Wrong title?** button. In the search you can set **Title** and **Year**, choose **Movie** or **TV series**, and tap **Search**. Other options:
- **Show without TMDB**: turn TMDB off for that item;
- **Reset to automatic**: undo a manual choice.

The badges "Chosen manually" and "TMDB off for this item" show the current state.

**VPN.** In some countries or networks, api.themoviedb.org and image.tmdb.org are blocked or slow. If posters don't load and the key is correct, use a VPN or change DNS. Local playback doesn't depend on TMDB.

## 8. Settings

Open **⚙ Settings** on the **Servers** screen.

### Playback
- **Default speed**: 0.5x–2.0x.
- **Seek step**: 10, 15, or 30 s. Used by double-tap.
- **Resume from last position**: remembers where you stopped and continues from there.
- **Autoplay next video**: when a video ends, plays the next file in the same folder.
- **Audio language**: preferred track — Any, ru, en, ja, uk, de, fr.
- **Decoding**: **Auto**, **Hardware**, or **Software**. If a video won't play or shows artifacts, try another mode.

### Subtitles
- **Subtitle language**: preferred language.
- **Auto-load external .srt/.ass/.vtt**: attaches subtitle files found next to the video (see section 9).
- **Font size**: 14–34 sp.
- **Text color**: White, Yellow, or Green.
- **Background**: None, Semi-transparent, or Black.

Subtitle timing offset is not supported yet (Media3 limitation).

### Network & buffer
- **Buffer (max)**: 30, 60, 120, or 300 s of video buffered ahead.
- **Read-ahead block**: 256–4096 KB (size of each read-ahead block).
- **Connection timeout**: 5–60 s.
- **Retry count**: 1, 2, 3, or 5 retries on network errors.
- **SMB dialect** and **SMB3 encryption**: see section 3.

### Interface
- **Theme**: System, Light, or Dark. **Pure black (AMOLED)** switches to a fully black background.
- **Sort by**: Name, Date, or Size.
- **Show hidden files**: shows dot-files and SMB hidden files.
- **Grid instead of list**: poster tiles instead of a list.
- **App language**: System, Русский, or English.

### Controls
- **Swipe for volume/brightness**: a vertical swipe on the left changes brightness, on the right changes volume.
- **Double-tap to seek**.
- **Picture-in-picture on minimize**.
- **Lock rotation in player**.
- **Horizontal swipe to seek**.
- **Background audio when minimized**.

### Posters & titles (TMDB)
See section 7.

### Data
- **Clear cache**: deletes temporary files and the TMDB cache, and closes SMB connections.
- **Clear watch history**: erases saved positions and Continue watching.
- **Export servers (no passwords)** and **Import servers**: see section 11.

## 9. Player

Tap a file to play it.

- **Tap** the middle to show or hide the controller: pause and the seek bar.
- **Double-tap** right to jump forward or left to jump back by the Seek step. A hint such as "+10 s" appears.
- **Horizontal swipe** seeks. A hint shows the offset and target time.
- **Vertical swipe**: left side changes brightness ("Brightness N%"), right side changes volume ("Volume N%").
- The speed button at top-right (e.g. **1.0x**) opens a 0.5x–2.0x speed menu.
- The **Tracks** button appears when there are several audio tracks or any subtitles. The dialog has **Audio** and **Subtitles** sections. **Off** disables subtitles. **Close** closes the dialog.
- **Picture-in-picture**: with "Picture-in-picture on minimize" on, pressing Home keeps the video playing in a small window.
- **Background audio**: with "Background audio when minimized" on, sound keeps playing after you minimize the app. Media controls appear in the notification shade and on the lock screen.

### External subtitles
Put the subtitle file in the same folder as the video, with the same base name:
```
Film.mkv
Film.srt          ← no language
Film.ru.srt       ← Russian
Film.eng.forced.ass
```
Supported formats are `.srt`, `.ass`, `.ssa`, and `.vtt`. A language code after the name (`ru`, `en`, `eng`…) is used to pick the track according to Subtitle language. "Auto-load external .srt/.ass/.vtt" must be on. Subtitles are attached for SMB, WebDAV, and HTTP listings, but not for a direct HTTP link.

## 10. Favourites, Continue watching, search

- **Favourites**: in the file list, tap the star on the right: **Add to favourites** or **Remove from favourites**. Both files and folders can be starred. Open the list with **★** on the **Servers** screen. When it's empty, it says "Nothing yet. Star files and folders to add them." The star isn't shown in Grid mode, so switch to the list.
- **Continue watching**: the **Servers** screen shows a row of unfinished videos with progress bars above the server list. Tap one to resume. The ✕ (**Remove from history**) removes it from the row.
- **Search**: in a folder, tap the magnifier (**Search**) and type in "Search in folder". It searches the current folder only. When nothing matches, it says "Nothing found". Tap ✕ to close the search.

## 11. Export and import servers

- **Settings → Data → Export servers (no passwords)** saves `remoteplayer-servers.json` to a location you choose. Passwords are **not** included.
- **Import servers**: pick such a file. You'll see "Imported N servers. Re-enter passwords." Then open each server and enter its password. If the file is invalid, you'll see "Import failed".

## 12. Troubleshooting (FAQ)

**Large MKV files (4K, high bitrate) stutter.**
- Raise **Buffer (max)** to 120–300 s and **Read-ahead block** to 2048–4096 KB.
- Use 5 GHz Wi-Fi (802.11ac/ax) and stay close to the router. 4K remuxes need 80–100+ Mbit/s of real throughput.
- Connect the NAS to the router by cable if you can.
- If the picture stutters while the network is fine, try **Decoding → Software** (or **Hardware**).
- For SMB, turn **SMB3 encryption** off unless the server requires it.

**Files are missing.** Only folders, videos (`.mp4 .mkv .avi .mov .webm .m4v .ts .wmv`), and subtitles are listed. Hidden files appear once you turn on "Show hidden files".

**SMB won't connect.**
- Make sure SMB2/SMB3 are enabled (SMB1 is not supported) and port 445 isn't blocked.
- Try **SMB dialect → SMB2** or **SMB3**.
- Use the IP address instead of the name.

**Works at home but not away from home.** Servers are on your LAN only. To reach them from outside, use a VPN into your home network, such as WireGuard or Tailscale.

**No background audio / no PiP.** Enable the matching options in **Settings → Controls**. Some phones also require allowing background activity in Android battery settings.

**Watch positions are gone.** **Clear watch history** erases them. Also check that "Resume from last position" is on.

**Posters don't load.** Check the key and that TMDB is enabled (section 7), and whether TMDB is reachable (VPN).

## 13. Privacy

- Server passwords and the TMDB key are stored encrypted on the device (the encryption key lives in the Android Keystore).
- The app has no analytics, telemetry, or ads. It connects only to your servers and, if "Load posters and titles" is on, to TMDB (api.themoviedb.org, image.tmdb.org). Only titles and years parsed from file names are sent to TMDB.
- Server exports contain no passwords.

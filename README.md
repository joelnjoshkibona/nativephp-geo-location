# GeoLocation plugin for NativePHP Mobile

Location in the foreground and in the background. Android only. No server, no Google Play Services needed.

NativePHP's own geolocation is a paid plugin. This one records in an Android **foreground service** that appends each fix to a buffer file on the phone, also while the app is closed. PHP reads the buffer later, saves the fixes where it wants, and trims what it saved.

## Installation

```bash
composer require blutrixx/nativephp-geo-location
php artisan native:plugin:register blutrixx/nativephp-geo-location
```

In a project that uses the local `packages/*` path repositories, require it as `*@dev`. Optional config: `php artisan vendor:publish --tag=geo-location-config` (notification text, default interval, distance and accuracy filter).

## Usage

```php
use Blutrixx\GeoLocation\Facades\GeoLocation;

// 1. Permissions, in two steps. Explain why before each.
GeoLocation::requestPermissions();                 // location (and notifications on Android 13+)
GeoLocation::checkPermissions();                   // ['foreground' => true, 'background' => false, ...]
GeoLocation::requestPermissions(background: true); // "Allow all the time" (Android 11+: opens Settings)

// 2. One position now
$here = GeoLocation::currentPosition(fine: true);  // ['lat' => ..., 'lng' => ..., 't' => ms, 'accuracy' => ...] or null

// 3. Record, also while the app is closed
GeoLocation::startTracking(id: 'places', intervalSeconds: 60, minDistanceMeters: 25);

// 4. Later, when the app opens or on a schedule
$cursor = cache('geo.cursor', 0);
$r = GeoLocation::drain('places', $cursor);        // ['fixes' => [...], 'cursor' => 1840, 'size' => 1840]
foreach ($r['fixes'] as $fix) { /* save */ }
GeoLocation::trim('places', $r['cursor']);         // free what is saved; offsets start again at 0
cache(['geo.cursor' => 0]);

GeoLocation::stopTracking('places');
```

`GeoLocation::status()` returns `tracking`, `lastFixAt`, `fixes`, `bufferBytes`, `providers` and `lastError`, which is what a "background health" screen needs. Every call returns an array; a failure is `['status' => 'error', 'code' => ..., 'message' => ...]`.

## How it works

* The service uses Android's fused provider (Android 12+), GPS and network location through the platform `LocationManager`, so it also works on phones without Google Play Services. Before Android 12 it listens to GPS and to the network provider (Wi-Fi, cell towers) together, because GPS alone gives few fixes indoors or in a pocket; a network fix is dropped while GPS is delivering. A fix is written when the phone moved `minDistanceMeters` and at least `intervalSeconds` passed.
* Android requires an ongoing notification while a foreground service runs. It is what keeps recording alive, so it cannot be hidden: write its text in `config/geo-location.php`.
* The settings of a running session are saved, so if Android kills the service it comes back by itself (`START_STICKY`), and the boot receiver starts it again after a restart or an app update. Android 12 and above can refuse to start a foreground service from the background; the error then appears in `status()['lastError']` and recording resumes when the app is opened.
* Fixes are JSON lines in the app's private storage (`files/blutrixx_geo/<id>.jsonl`). Cursors are byte offsets.

## Permissions

| Permission | Why |
| --- | --- |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Position |
| `ACCESS_BACKGROUND_LOCATION` | "Allow all the time", so recording continues when the app is closed |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | The recording service (Android 14 needs the location type) |
| `POST_NOTIFICATIONS` | The ongoing notification is visible (Android 13+) |
| `RECEIVE_BOOT_COMPLETED` | Resume after a restart |

Google Play asks apps that use background location for a declaration form and a prominent in-app disclosure before the request. Declaring these permissions in an app that does not record in the background can get it flagged: install this plugin only in apps that need it.

## The phone's state with each fix (1.2.0)

When `blutrixx/nativephp-device-utils` (1.9.0 or later) is in the app, each fix also carries `st`, a short reading of the phone taken at that moment: `{"b":85,"c":1,"n":"w","g":"4G","s":3,"p":0}` (battery percent, charging, network w = wifi, m = mobile, e = ethernet, x = none, mobile generation, signal 0 to 4, power saving). It is found by reflection, so without device-utils nothing changes and `st` is absent. It holds nothing that identifies the person or the place (no wifi names, no identifiers), and it arrives in `drain()` with the fix, so an app can keep a history of the phone's state taken with the app closed.

## Was the phone still? (1.3.0)

Indoors, the network and GPS fixes of a phone lying on a table wander by hundreds of metres, sometimes kilometres, while claiming 50 m. So each fix also carries `mv`: `0` when the phone's low-power motion sensors (`TYPE_SIGNIFICANT_MOTION`, and the maker's `TYPE_MOTION_DETECT` where there is one) reported no motion for the last 4 minutes, `1` when they did. A phone with neither sensor sends no `mv`. No permission is needed, and the sensors wake the app only when motion starts. An app can use it to ignore position jumps of a phone that did not move. The sensors in use are named in the app log when recording starts.

## Limits

* Android only. Makers that restrict background apps (battery "restricted", auto-start lists) can stop the service: check the app's battery setting.
* No geofencing. Work out visits from the fixes in PHP.
* A name close to NativePHP's own `Geolocation` facade: this one is `Blutrixx\GeoLocation\Facades\GeoLocation`, bridge functions `GeoLocation.*`. Do not install both in one app.

## Device results

Telpo M1K, Android 12, NativePHP Mobile 4.7.0, driven with `adb` (3 November 2026):

| Check | Result |
| --- | --- |
| Permissions granted, `startTracking()` | Foreground service started (`foregroundServiceType` location), fused and GPS requested |
| App swiped away, screen locked, 5 minutes | Service stayed alive and kept its location requests registered |
| A fix arriving while the app was closed | Written to the buffer file (`{"lat":-6.7919,"lng":39.2083,"t":...,"provider":"gps","accuracy":8}`) |
| `drain()` after reopening the app | Returned the fix and cursor 78; `status()` showed `lastFixAt` and `fixes: 1` |
| `stopTracking()` | Service stopped |
| Real satellite fixes | Not obtained: the test phone is a POS terminal indoors with no SIM and no network location, so fixes were injected with `cmd location providers` |
| Reboot resume, battery drain, Android 13 and above | Not run |

## Tests

`./vendor/bin/pest` checks the manifest against the Kotlin and PHP sources. Behaviour on a phone is tested with `adb` in a spike app.

## License

MIT

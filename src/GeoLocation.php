<?php

namespace Blutrixx\GeoLocation;

/**
 * Location in the foreground and in the background.
 *
 * Recording runs in an Android foreground service that appends each fix to a buffer file on the phone, also while the
 * app is closed. PHP reads the buffer later with drain(), saves the fixes where it wants, then trims what it saved.
 * A cursor is a byte offset into the buffer: keep the one drain() returns and pass it next time.
 *
 * Every method returns an array. A failed call returns ['status' => 'error', 'code' => ..., 'message' => ...].
 * A fix is ['lat', 'lng', 't' (epoch milliseconds), 'accuracy'?, 'speed'?, 'altitude'?, 'heading'?, 'provider'].
 */
class GeoLocation
{
    /**
     * What the phone allows now.
     *
     * @return array{foreground?: bool, fine?: bool, background?: bool, notifications?: bool, locationOn?: bool, tracking?: bool, sdk?: int}
     */
    public function checkPermissions(): array
    {
        return $this->call('GeoLocation.CheckPermissions');
    }

    /**
     * Ask for location (and notifications on Android 13+). Android asks in two steps: call this, check
     * checkPermissions(), then call it again with $background = true to ask for "all the time" (on Android 11+ the
     * person sets it in Settings). Explain why before each step.
     *
     * @return array{asked?: string}  'foreground', 'background' or 'none'
     */
    public function requestPermissions(bool $background = false): array
    {
        return $this->call('GeoLocation.RequestPermissions', ['background' => $background]);
    }

    /**
     * One position now: a known one no older than $maxAgeSeconds, or a fresh fix within $timeoutSeconds.
     *
     * @return array{lat: float, lng: float, t: int, accuracy?: float}|null  null when none could be found
     */
    public function currentPosition(bool $fine = false, int $timeoutSeconds = 15, int $maxAgeSeconds = 60): ?array
    {
        $r = $this->call('GeoLocation.GetCurrentPosition', [
            'fine' => $fine, 'timeoutSeconds' => $timeoutSeconds, 'maxAgeSeconds' => $maxAgeSeconds,
        ]);

        return isset($r['fix']) ? (json_decode($r['fix'], true) ?: null) : null;
    }

    /**
     * Start recording. Needs location permission; for the app to keep recording when closed the person must also have
     * allowed "all the time". Android shows an ongoing notification while it runs.
     */
    public function startTracking(
        string $id = 'default',
        ?int $intervalSeconds = null,
        ?int $minDistanceMeters = null,
        bool $highAccuracy = false,
        ?string $title = null,
        ?string $text = null,
    ): array {
        return $this->call('GeoLocation.StartTracking', [
            'id' => $id,
            'intervalSeconds' => $intervalSeconds ?? config('geo-location.interval_seconds'),
            'minDistanceMeters' => $minDistanceMeters ?? config('geo-location.min_distance_meters'),
            'maxAccuracyMeters' => config('geo-location.max_accuracy_meters'),
            'highAccuracy' => $highAccuracy,
            'title' => $title ?? config('geo-location.notification_title'),
            'text' => $text ?? config('geo-location.notification_text'),
        ]);
    }

    /** Stop recording. The buffer stays so a last drain() can collect the tail, unless $clearBuffer is true. */
    public function stopTracking(string $id = 'default', bool $clearBuffer = false): array
    {
        return $this->call('GeoLocation.StopTracking', ['id' => $id, 'clearBuffer' => $clearBuffer]);
    }

    /**
     * Read fixes recorded since $cursor (0 for the start).
     *
     * @return array{count?: int, cursor?: int, size?: int, fixes: array<int, array<string, mixed>>}
     */
    public function drain(string $id = 'default', int $cursor = 0, int $limit = 500): array
    {
        $r = $this->call('GeoLocation.Drain', ['id' => $id, 'cursor' => $cursor, 'limit' => $limit]);
        $r['fixes'] = isset($r['fixes']) && is_string($r['fixes']) ? (json_decode($r['fixes'], true) ?? []) : [];

        return $r;
    }

    /** Delete fixes before $cursor, once they are saved. Offsets start again from 0 afterwards. */
    public function trim(string $id, int $cursor): array
    {
        return $this->call('GeoLocation.Trim', ['id' => $id, 'cursor' => $cursor]);
    }

    /**
     * @return array{tracking?: bool, id?: string, startedAt?: int, lastFixAt?: int, fixes?: int, bufferBytes?: int, providers?: string, lastError?: string}
     */
    public function status(?string $id = null): array
    {
        return $this->call('GeoLocation.Status', $id ? ['id' => $id] : []);
    }

    /** @param  'location'|'app'  $page */
    public function openSettings(string $page = 'app'): array
    {
        return $this->call('GeoLocation.OpenSettings', ['page' => $page]);
    }

    private function call(string $function, array $params = []): array
    {
        if (! function_exists('nativephp_call')) {
            return ['status' => 'error', 'code' => 'NOT_ON_DEVICE', 'message' => 'GeoLocation only works inside the NativePHP app.'];
        }

        $result = nativephp_call($function, json_encode($params === [] ? new \stdClass() : $params));

        return $result ? (json_decode($result, true) ?? []) : [];
    }
}

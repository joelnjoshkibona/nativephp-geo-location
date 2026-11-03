<?php

namespace Blutrixx\GeoLocation\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static array checkPermissions()
 * @method static array requestPermissions(bool $background = false)
 * @method static array|null currentPosition(bool $fine = false, int $timeoutSeconds = 15, int $maxAgeSeconds = 60)
 * @method static array startTracking(string $id = 'default', ?int $intervalSeconds = null, ?int $minDistanceMeters = null, bool $highAccuracy = false, ?string $title = null, ?string $text = null)
 * @method static array stopTracking(string $id = 'default', bool $clearBuffer = false)
 * @method static array drain(string $id = 'default', int $cursor = 0, int $limit = 500)
 * @method static array trim(string $id, int $cursor)
 * @method static array status(?string $id = null)
 * @method static array openSettings(string $page = 'app')
 *
 * @see \Blutrixx\GeoLocation\GeoLocation
 */
class GeoLocation extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Blutrixx\GeoLocation\GeoLocation::class;
    }
}

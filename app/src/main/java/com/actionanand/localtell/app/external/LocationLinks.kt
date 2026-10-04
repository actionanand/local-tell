package com.actionanand.localtell.app.external

import android.net.Uri
import java.net.URLEncoder
import java.util.Locale

data class RideDestination(val latitude: Double, val longitude: Double, val label: String, val address: String)

object MapLinkBuilder {
    fun googleMapsUrl(latitude: Double, longitude: Double): String =
        "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(coordinateText(latitude, longitude), "UTF-8")}"

    fun googleMaps(latitude: Double, longitude: Double): Uri = Uri.parse(googleMapsUrl(latitude, longitude))

    fun coordinateText(latitude: Double, longitude: Double): String = String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
}

object RideLinkBuilder {
    const val UBER_PACKAGE = "com.ubercab"

    /** Lets Uber determine the rider's pickup and uses the LocalTell location as destination. */
    fun uberAsDestination(destination: RideDestination): Uri = Uri.parse(uberAsDestinationUrl(destination))

    /** Uses the LocalTell location as Uber pickup, leaving destination selection to the rider. */
    fun uberAsPickup(location: RideDestination): Uri = Uri.parse(uberAsPickupUrl(location))

    fun uberAsDestinationUrl(destination: RideDestination): String = uberUrl(
        "pickup" to "my_location",
        "dropoff[latitude]" to destination.latitude.toString(),
        "dropoff[longitude]" to destination.longitude.toString(),
        "dropoff[nickname]" to destination.label,
        "dropoff[formatted_address]" to destination.address,
    )

    fun uberAsPickupUrl(location: RideDestination): String = uberUrl(
        "pickup[latitude]" to location.latitude.toString(),
        "pickup[longitude]" to location.longitude.toString(),
        "pickup[nickname]" to location.label,
        "pickup[formatted_address]" to location.address,
    )

    private fun uberUrl(vararg parameters: Pair<String, String>): String =
        "uber://riderequest?" + parameters.joinToString("&") { (name, value) ->
            val encodedName = URLEncoder.encode(name, "UTF-8")
            val encodedValue = URLEncoder.encode(value, "UTF-8")
            "$encodedName=$encodedValue"
        }
}

package com.dennis.colourprofiles

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class ToggleTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val on = !Prefs.enabled(this)
        Prefs.setEnabled(this, on)
        if (!on) ShizukuBridge.applyMode(Prefs.defaultMode(this), Prefs.useMdnie(this))
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = Prefs.enabled(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            !on -> getString(R.string.tile_off)
            ShizukuBridge.status() == ShizukuBridge.Status.NOT_RUNNING ->
                getString(R.string.tile_no_shizuku)
            else -> getString(R.string.tile_on)
        }
        tile.updateTile()
    }
}

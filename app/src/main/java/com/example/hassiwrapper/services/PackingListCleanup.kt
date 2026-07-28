package com.example.hassiwrapper.services

import com.example.hassiwrapper.data.db.dao.SmsPackingListSpoolDao
import com.example.hassiwrapper.data.db.dao.SmsSpoolDao
import com.example.hassiwrapper.data.db.dao.SmsTransferDao
import com.example.hassiwrapper.data.db.dao.SmsVehicleDao

/** A Packing List that was sent (spools in_transit, vehicle on_route, SEND transfer recorded)
 *  is being removed before it was ever received/confirmed — either a manual hard-delete or a
 *  local ghost PL dropped after losing the server's vehicle-conflict guard (409). Either way,
 *  the vehicle and its spools must be unwound or the vehicle is stuck "on route" forever (only
 *  a receive normally clears that flag) and the SEND transfer is left dangling.
 *
 *  Takes the id/vehicleId rather than the entity so spool/transfer cleanup still runs even when
 *  the caller's PL lookup came back null (deleted out from under it) — only the vehicle-release
 *  step, which needs a vehicle id, is skipped in that case. */
suspend fun releaseDanglingSendForPackingList(
    packingListId: Long,
    vehicleId: Long?,
    smsSpoolDao: SmsSpoolDao,
    smsPackingListSpoolDao: SmsPackingListSpoolDao,
    smsTransferDao: SmsTransferDao,
    smsVehicleDao: SmsVehicleDao
) {
    val spools = smsSpoolDao.getByPackingList(packingListId)
    spools.forEach {
        smsSpoolDao.updatePackingList(it.spool_id, null)
        smsSpoolDao.updateInTransit(it.spool_id, false)
    }
    smsPackingListSpoolDao.deleteByPackingList(packingListId)

    val sendTransfers = smsTransferDao.getSendByPackingList(packingListId)
    if (sendTransfers.isNotEmpty()) {
        val transferIds = sendTransfers.map { it.transfer_id }
        smsTransferDao.deleteSpoolsByTransferIds(transferIds)
        smsTransferDao.deleteByIds(transferIds)
    }

    if (vehicleId == null) return
    val stillInTransit = smsSpoolDao.countInTransitByVehicle(vehicleId)
    if (stillInTransit == 0) {
        smsVehicleDao.setOffRoute(vehicleId)
    }
}

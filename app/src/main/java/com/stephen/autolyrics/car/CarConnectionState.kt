package com.stephen.autolyrics.car

import android.content.Context
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/** 手機同車機嘅連接狀態，用嚟喺手機畫面顯示而家通唔通。 */
enum class CarLink {
    /** 冇連接住任何車機。 */
    DISCONNECTED,

    /** Android Auto 投影緊（手機出畫面畀車機）—— 我哋關心嘅就係呢個。 */
    PROJECTION,

    /** 行緊 Android Automotive OS，即係 app 直接裝喺車上面。 */
    NATIVE,
}

/**
 * 包住 androidx 個 CarConnection。
 *
 * 佢底層係一個 ContentProvider query + broadcast，Android Auto 冇裝或者冇連接
 * 都答到 —— 所以喺手機淨係開住 app 都用得，唔使真係插咗車先有嘢睇。
 *
 * CarConnection 回傳 LiveData。呢度用 callbackFlow 自己 observe，唔用
 * lifecycle-livedata-ktx 個 asFlow()，慳返一個淨係為咗一個值嘅依賴。
 *
 * 成個 builder 連同 awaitClose 都 flowOn(Main)，因為 observeForever /
 * removeObserver 兩者都規定要喺 main thread 行。
 */
object CarConnectionState {

    fun flow(context: Context): Flow<CarLink> = callbackFlow {
        val liveData = CarConnection(context).type
        val observer = Observer<Int> { type ->
            trySend(
                when (type) {
                    CarConnection.CONNECTION_TYPE_PROJECTION -> CarLink.PROJECTION
                    CarConnection.CONNECTION_TYPE_NATIVE -> CarLink.NATIVE
                    else -> CarLink.DISCONNECTED
                }
            )
        }
        liveData.observeForever(observer)
        awaitClose { liveData.removeObserver(observer) }
    }.flowOn(Dispatchers.Main)
}

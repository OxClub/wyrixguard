package com.wyrix.guard.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import com.wyrix.guard.data.SecureStore
import com.wyrix.guard.data.remote.SupabaseApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class Plan(val productId: String, val title: String, val price: String, internal val details: ProductDetails)

/**
 * Google Play Billing for subscriptions. The purchase token is sent to our Edge Function, which verifies it with
 * the Play Developer API and writes the entitlement. The app NEVER grants premium from a local flag.
 */
@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val ctx: Context, private val api: SupabaseApi, private val store: SecureStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _plans = MutableStateFlow<List<Plan>>(emptyList())
    private val _message = MutableStateFlow<String?>(null)
    private val _tier = MutableStateFlow("free")
    val plans: StateFlow<List<Plan>> = _plans
    val message: StateFlow<String?> = _message
    val tier: StateFlow<String> = _tier

    private val client: BillingClient = BillingClient.newBuilder(ctx)
        .setListener { result, purchases -> onPurchases(result, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private suspend fun connect(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { c ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(r: BillingResult) { if (c.isActive) c.resume(r.responseCode == BillingClient.BillingResponseCode.OK) }
                override fun onBillingServiceDisconnected() {}
            })
        }
    }

    suspend fun loadPlans() {
        if (!connect()) { _message.value = "Google Play Billing is not available on this device."; return }
        val params = QueryProductDetailsParams.newBuilder().setProductList(PRODUCT_IDS.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
        }).build()
        val res = client.queryProductDetails(params)
        _plans.value = (res.productDetailsList ?: emptyList()).mapNotNull { pd ->
            val offer = pd.subscriptionOfferDetails?.firstOrNull() ?: return@mapNotNull null
            Plan(pd.productId, pd.name, offer.pricingPhases.pricingPhaseList.last().formattedPrice, pd)
        }
        if (_plans.value.isEmpty()) _message.value = "No subscription products are available yet."
    }

    fun launch(activity: Activity, plan: Plan) {
        val uid = store.userId ?: return
        val offer = plan.details.subscriptionOfferDetails?.firstOrNull() ?: return
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(plan.details).setOfferToken(offer.offerToken).build()))
            .setObfuscatedAccountId(sha256Hex(uid))   // binds the purchase to this account; verified server-side
            .build()
        client.launchBillingFlow(activity, flow)
    }

    suspend fun restore() {
        if (!connect()) return
        val res = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
        res.purchasesList.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach { verify(it) }
    }

    private fun onPurchases(r: BillingResult, purchases: List<Purchase>?) {
        when (r.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.forEach { p ->
                if (p.purchaseState == Purchase.PurchaseState.PURCHASED) scope.launch { verify(p) }
                else _message.value = "Your purchase is pending. Premium unlocks after Google Play confirms payment."
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> _message.value = null
            else -> _message.value = "Purchase failed (${r.responseCode})."
        }
    }

    private suspend fun verify(p: Purchase) {
        try {
            val res = withContext(Dispatchers.IO) { api.fn("verify-purchase", buildJsonObject { put("purchase_token", p.purchaseToken) }) }
            _tier.value = res["tier"]?.jsonPrimitive?.contentOrNull ?: "free"
            _message.value = if (_tier.value == "premium") "Premium is active." else "Purchase not active yet."
        } catch (e: Exception) { _message.value = "Could not verify the purchase yet; it will be retried. (${(e as? SupabaseApi.ApiException)?.code ?: "network"})" }
    }

    suspend fun refreshTier() {
        val rows = withContext(Dispatchers.IO) { runCatching { api.restArray("subscriptions?select=tier,status,expires_at") }.getOrNull() } ?: return
        val o = rows.firstOrNull() as? kotlinx.serialization.json.JsonObject
        val ok = o != null && o["tier"]?.jsonPrimitive?.contentOrNull == "premium" &&
            o["status"]?.jsonPrimitive?.contentOrNull in setOf("active", "grace") &&
            (o["expires_at"]?.jsonPrimitive?.contentOrNull?.let { com.wyrix.guard.core.Iso.toMs(it) > System.currentTimeMillis() } == true)
        _tier.value = if (ok) "premium" else "free"
    }

    companion object {
        val PRODUCT_IDS = listOf("wyrix_premium_monthly", "wyrix_premium_yearly")
        fun sha256Hex(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

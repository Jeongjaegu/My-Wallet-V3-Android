package piuk.blockchain.android.ui.buysell.payment

import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.functions.BiFunction
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.subjects.PublishSubject
import piuk.blockchain.android.R
import piuk.blockchain.android.data.payments.SendDataManager
import piuk.blockchain.android.ui.buysell.payment.models.OrderType
import piuk.blockchain.android.util.StringUtils
import piuk.blockchain.android.util.extensions.addToCompositeDisposable
import piuk.blockchain.androidbuysell.datamanagers.CoinifyDataManager
import piuk.blockchain.androidbuysell.models.coinify.KycResponse
import piuk.blockchain.androidbuysell.models.coinify.Medium
import piuk.blockchain.androidbuysell.models.coinify.PaymentMethod
import piuk.blockchain.androidbuysell.models.coinify.Quote
import piuk.blockchain.androidbuysell.models.coinify.Trader
import piuk.blockchain.androidbuysell.services.ExchangeService
import piuk.blockchain.androidcore.data.currency.BTCDenomination
import piuk.blockchain.androidcore.data.currency.CurrencyFormatManager
import piuk.blockchain.androidcore.data.payload.PayloadDataManager
import piuk.blockchain.androidcore.utils.extensions.applySchedulers
import piuk.blockchain.androidcoreui.ui.base.BasePresenter
import piuk.blockchain.androidcoreui.ui.customviews.ToastCustom
import timber.log.Timber
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.ParseException
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.absoluteValue
import kotlin.properties.Delegates


class BuySellBuildOrderPresenter @Inject constructor(
        private val coinifyDataManager: CoinifyDataManager,
        private val sendDataManager: SendDataManager,
        private val payloadDataManager: PayloadDataManager,
        private val exchangeService: ExchangeService,
        private val currencyFormatManager: CurrencyFormatManager,
        private val stringUtils: StringUtils
) : BasePresenter<BuySellBuildOrderView>() {

    val receiveSubject: PublishSubject<String> = PublishSubject.create<String>()
    val sendSubject: PublishSubject<String> = PublishSubject.create<String>()
    var account by Delegates.observable(payloadDataManager.defaultAccount) { _, old, new ->
        if (old != new) {
            view.updateAccountSelector(new.label)
            // TODO: Recalculate max/min values
        }
    }

    private var latestQuote: Quote? = null
    private var selectedCurrency: String? = null

    private val emptyQuote
        get() = Quote(
                null,
                selectedCurrency!!,
                "BTC",
                0.0,
                0.0,
                "",
                ""
        )

    private val tokenSingle: Single<String>
        get() = exchangeService.getExchangeMetaData()
                .addToCompositeDisposable(this)
                .applySchedulers()
                .singleOrError()
                .doOnError { view.onFatalError() }
                .map { it.coinify!!.token }

    private val inMedium: Single<Medium>
        get() = when (view.orderType) {
            OrderType.Sell -> Single.just(Medium.Blockchain)
            OrderType.Buy -> tokenSingle
                    .flatMap { coinifyDataManager.getKycReviews(it) }
                    .map {
                        if (it.hasPendingKyc())
                            Medium.Card
                        else
                            Medium.Bank
                    }
        }

    // TODO: 2) Cache buy limits for chosen payment type, both max and min
    // TODO: 2A) Figure out how we handle not knowing payment type? Web just assumes payment type max
    // TODO: 4) Check amounts against limits, notify UI if min < x > max
    // TODO: 5) Prevent users from entering more than 2 DP for money, 8 DP for BTC

    override fun onViewReady() {
        // Display Accounts selector if necessary
        if (payloadDataManager.accounts.size > 1) {
            view.displayAccountSelector(account.label)
        }

        // Get quote for value of 1 BTC for UI using default currency
        tokenSingle
                .doOnSubscribe { view.renderSpinnerStatus(SpinnerStatus.Loading) }
                .flatMapCompletable { token ->

                    Observable.zip(coinifyDataManager.getTrader(token).toObservable(),
                            inMedium.toObservable(),
                            BiFunction<Trader, Medium, Boolean> { trader, inMedium ->
                                // TODO: Minimum sell plus fee (for sell only)
                                // This requires trader info + bitcoin limits (for sell only)
                                // Web currently display the limits via quote instead..?
                                getExchangeRate(token, -1.0, trader.defaultCurrency)
                                        .toObservable()
                                        .flatMap {
                                            getPaymentMethods(
                                                    token,
                                                    inMedium
                                            ).toObservable()
                                        }
                                        .doOnNext {
                                            loadCurrencies(
                                                    it,
                                                    inMedium,
                                                    trader.defaultCurrency
                                            )
                                        }
                                        .doOnNext {
                                            loadLimits(
                                                    inMedium,
                                                    trader.defaultCurrency,
                                                    it
                                            )
                                        }
                                        .subscribe()
                                true
                            }).ignoreElements()
                }
                .subscribe()

        sendSubject.applyDefaults()
                .flatMapSingle { amount ->
                    tokenSingle.flatMap {
                        coinifyDataManager.getQuote(
                                it,
                                amount.unaryMinus(),
                                selectedCurrency!!,
                                "BTC"
                        ).doOnSuccess { latestQuote = it }
                                .doOnError { setUnknownErrorState(it) }
                                .onErrorReturn { emptyQuote }
                                .doAfterSuccess { view.showQuoteInProgress(false) }
                    }
                }
                .doOnNext { updateRecieveAmount(it.quoteAmount) }
                .subscribeBy(onError = { setUnknownErrorState(it) })

        receiveSubject.applyDefaults()
                .flatMapSingle { amount ->
                    tokenSingle.flatMap {
                        coinifyDataManager.getQuote(
                                it,
                                amount,
                                "BTC",
                                selectedCurrency!!
                        ).doOnSuccess { latestQuote = it }
                                .doOnError { setUnknownErrorState(it) }
                                .onErrorReturn { emptyQuote }
                                .doAfterSuccess { view.showQuoteInProgress(false) }
                    }
                }
                .doOnNext { updateSendAmount(it.quoteAmount.absoluteValue) }
                .subscribeBy(onError = { setUnknownErrorState(it) })
    }

    private fun updateRecieveAmount(quoteAmount: Double) {
        val formatted = currencyFormatManager
                .getFormattedBchValue(BigDecimal.valueOf(quoteAmount), BTCDenomination.BTC)
        view.updateReceiveAmount(formatted)
    }

    private fun updateSendAmount(quoteAmount: Double) {
        val formatted = currencyFormatManager
                .getFiatFormat(selectedCurrency!!).format(quoteAmount)
        view.updateSendAmount(formatted)
    }

    fun onCurrencySelected(currency: String) {
        tokenSingle
                .doOnSubscribe { view.renderExchangeRate(ExchangeRateStatus.Loading) }
                .flatMap { getExchangeRate(it, -1.0, currency) }
                .subscribe()
    }

    private fun setUnknownErrorState(throwable: Throwable) {
        Timber.e(throwable)
        view.clearEditTexts()
        view.setButtonEnabled(false)
        view.showToast(R.string.buy_sell_error_fetching_quote, ToastCustom.TYPE_ERROR)
    }

    private fun getPaymentMethods(token: String, inMedium: Medium): Single<PaymentMethod> =
            coinifyDataManager.getPaymentMethods(token)
                    .filter { it.inMedium == inMedium }
                    .firstOrError()

    private fun loadCurrencies(
            paymentMethod: PaymentMethod,
            inMedium: Medium,
            userCurrency: String
    ): Observable<PaymentMethod> {

        val currencies = when (inMedium) {
            Medium.Blockchain -> paymentMethod.outCurrencies.toMutableList() // Sell
            else -> paymentMethod.inCurrencies.toMutableList() // Buy
        }

        selectedCurrency = if (currencies.contains(userCurrency)) {
            val index = currencies.indexOf(userCurrency)
            currencies.removeAt(index)
            currencies.add(0, userCurrency)
            userCurrency
        } else {
            currencies[0]
        }

        view.renderSpinnerStatus(SpinnerStatus.Data(currencies))
        return Observable.just(paymentMethod)
    }

    private fun loadLimits(inMedium: Medium, userCurrency: String, paymentMethod: PaymentMethod) {
        when (inMedium) {
            Medium.Blockchain -> loadSellLimits(paymentMethod)
            else -> loadBuyLimits(userCurrency, paymentMethod)
        }
    }

    private fun loadBuyLimits(userCurrency: String, paymentMethod: PaymentMethod) {

        val currencies = paymentMethod.inCurrencies.toMutableList()
        selectedCurrency = if (currencies.contains(userCurrency)) {
            val index = currencies.indexOf(userCurrency)
            currencies.removeAt(index)
            currencies.add(0, userCurrency)
            userCurrency
        } else {
            currencies[0]
        }

        val limitAmount = when (selectedCurrency) {
            "GBP" -> "${paymentMethod.limitInAmounts.gbp} GBP"
            "DKK" -> "${paymentMethod.limitInAmounts.dkk} DKK"
            "EUR" -> "${paymentMethod.limitInAmounts.eur} EUR"
            else -> "${paymentMethod.limitInAmounts.usd} USD"
        }

        view.renderBuyLimit(LimitStatus.Data(R.string.buy_sell_remaining_buy_limit, limitAmount))
    }

    private fun loadSellLimits(paymentMethod: PaymentMethod) {
        //TODO Check minimum limit againts balance
        val minimumAmount = "${paymentMethod.minimumInAmounts.btc} BTC"
        view.renderSellLimit(LimitStatus.Data(R.string.buy_sell_minimum_sell_limit, minimumAmount))
    }

    //region Observables
    private fun getExchangeRate(token: String, amount: Double, currency: String): Single<Quote> =
            coinifyDataManager.getQuote(token, amount, "BTC", currency)
                    .doOnSuccess {
                        val valueWithSymbol =
                                currencyFormatManager.getFormattedFiatValueWithSymbol(
                                        it.quoteAmount,
                                        it.quoteCurrency,
                                        view.locale
                                )

                        view.renderExchangeRate(ExchangeRateStatus.Data("@ $valueWithSymbol"))

                    }
                    .doOnError { view.renderExchangeRate(ExchangeRateStatus.Failed) }

    private fun PublishSubject<String>.applyDefaults(): Observable<Double> = this.doOnNext {
        //        view.clearError()
        view.setButtonEnabled(false)
        view.showQuoteInProgress(true)
    }.debounce(1000, TimeUnit.MILLISECONDS)
            // Here we kill any quotes in flight already, as they take up to ten seconds to fulfill
            .doOnNext { compositeDisposable.clear() }
            // Strip out localised information for predictable formatting
            .map { it.sanitise().parse(view.locale) }
            // Logging
            .doOnError(Timber::wtf)
            // Return zero if empty or some other error
            .onErrorReturn { BigDecimal.ZERO }
            // Scheduling for UI updates if necessary
            .observeOn(AndroidSchedulers.mainThread())
            // If zero, clear all EditTexts and reset UI state
            .doOnNext {
                if (it <= BigDecimal.ZERO) {
                    view.clearEditTexts()
                    view.setButtonEnabled(false)
                    view.showQuoteInProgress(false)
                }
            }
            // Don't pass zero events to the API as they're invalid
            .filter { it > BigDecimal.ZERO }
            // To double, as API requires it
            .map { it.toDouble() }
    //endregion

    //region Extension Functions
    private fun String.sanitise() = if (isNotEmpty()) this else "0"

    @Throws(ParseException::class)
    private fun String.parse(locale: Locale): BigDecimal {
        val format = NumberFormat.getNumberInstance(locale)
        if (format is DecimalFormat) {
            format.isParseBigDecimal = true
        }
        return format.parse(this.replace("[^\\d.,]".toRegex(), "")) as BigDecimal
    }

    private fun List<KycResponse>.hasPendingKyc(): Boolean = this.any { it.state.isProcessing() }

    //endregion

    sealed class ExchangeRateStatus {

        object Loading : ExchangeRateStatus()
        data class Data(val formattedQuote: String) : ExchangeRateStatus()
        object Failed : ExchangeRateStatus()

    }

    sealed class SpinnerStatus {

        object Loading : SpinnerStatus()
        data class Data(val currencies: List<String>) : SpinnerStatus()
        object Failure : SpinnerStatus()

    }

    sealed class LimitStatus {

        object Loading : LimitStatus()
        data class Data(val textResourceId: Int, val limit: String) : LimitStatus()
        object Failure : LimitStatus()

    }
}
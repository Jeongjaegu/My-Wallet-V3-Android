package piuk.blockchain.android.data.datamanagers

import info.blockchain.balance.AccountKey
import info.blockchain.balance.BalanceReporter
import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.CryptoValue
import info.blockchain.wallet.payload.PayloadManager
import io.reactivex.Observable
import io.reactivex.Single
import piuk.blockchain.android.data.balance.adapters.toBalanceReporter
import piuk.blockchain.androidcore.data.bitcoincash.BchDataManager
import piuk.blockchain.androidcore.data.ethereum.EthDataManager
import piuk.blockchain.android.ui.account.ItemAccount
import piuk.blockchain.androidcore.data.currency.CurrencyState
import piuk.blockchain.androidcore.data.transactions.TransactionListStore
import piuk.blockchain.androidcore.data.transactions.models.BchDisplayable
import piuk.blockchain.androidcore.data.transactions.models.BtcDisplayable
import piuk.blockchain.androidcore.data.transactions.models.Displayable
import piuk.blockchain.androidcore.data.transactions.models.EthDisplayable
import piuk.blockchain.androidcore.injection.PresenterScope
import piuk.blockchain.androidcore.utils.extensions.applySchedulers
import java.util.ArrayList
import java.util.HashMap
import javax.inject.Inject

@PresenterScope
class TransactionListDataManager @Inject constructor(
    private val payloadManager: PayloadManager,
    private val ethDataManager: EthDataManager,
    private val bchDataManager: BchDataManager,
    private val transactionListStore: TransactionListStore,
    private val currencyState: CurrencyState
) {

    fun fetchTransactions(
        itemAccount: ItemAccount,
        limit: Int,
        offset: Int
    ): Observable<List<Displayable>> {

        val observable: Observable<List<Displayable>> = when (currencyState.cryptoCurrency) {
            CryptoCurrency.BTC -> fetchBtcTransactions(itemAccount, limit, offset)
            CryptoCurrency.ETHER -> getEthereumObservable()
            CryptoCurrency.BCH -> fetchBchTransactions(itemAccount, limit, offset)
            else -> throw IllegalArgumentException("Cryptocurrency ${currencyState.cryptoCurrency.unit} not supported")
        }

        return observable.doOnNext { insertTransactionList(it.toMutableList()) }
            .map { transactionListStore.list }
            .doOnError { emptyList<Displayable>() }
            .applySchedulers()
    }

    internal fun fetchBtcTransactions(
        itemAccount: ItemAccount,
        limit: Int,
        offset: Int
    ): Observable<List<Displayable>> =
        when (itemAccount.type) {
            ItemAccount.TYPE.ALL_ACCOUNTS_AND_LEGACY -> getAllTransactionsObservable(
                limit,
                offset
            )
            ItemAccount.TYPE.ALL_LEGACY -> getLegacyObservable(limit, offset)
            ItemAccount.TYPE.SINGLE_ACCOUNT -> getAccountObservable(
                itemAccount.address!!,
                limit,
                offset
            )
        }

    internal fun fetchBchTransactions(
        itemAccount: ItemAccount,
        limit: Int,
        offset: Int
    ): Observable<List<Displayable>> =
        when (itemAccount.type) {
            ItemAccount.TYPE.ALL_ACCOUNTS_AND_LEGACY -> getBchAllTransactionsObservable(
                limit,
                offset
            )
            ItemAccount.TYPE.ALL_LEGACY -> getBchLegacyObservable(limit, offset)
            ItemAccount.TYPE.SINGLE_ACCOUNT -> getBchAccountObservable(
                itemAccount.address!!,
                limit,
                offset
            )
        }

    /**
     * Returns a list of [Displayable] objects generated by [getTransactionList]
     *
     * @return A list of Txs sorted by date.
     */
    fun getTransactionList(): List<Displayable> = transactionListStore.list

    /**
     * Resets the list of Transactions.
     */
    fun clearTransactionList() {
        transactionListStore.clearList()
    }

    /**
     * Allows insertion of a single new [Displayable] into the main transaction list.
     *
     * @param transaction A new, most likely temporary [Displayable]
     * @return An updated list of Txs sorted by date
     */
    fun insertTransactionIntoListAndReturnSorted(transaction: Displayable): List<Displayable> {
        transactionListStore.insertTransactionIntoListAndSort(transaction)
        return transactionListStore.list
    }

    /**
     * Get total BTC balance from [ItemAccount].
     *
     * @param itemAccount [ItemAccount]
     * @return A BTC value as a long.
     */
    fun getBtcBalance(itemAccount: ItemAccount): Long = when (itemAccount.type) {
        ItemAccount.TYPE.ALL_ACCOUNTS_AND_LEGACY -> payloadManager.walletBalance.toLong()
        ItemAccount.TYPE.ALL_LEGACY -> payloadManager.importedAddressesBalance.toLong()
        ItemAccount.TYPE.SINGLE_ACCOUNT -> payloadManager.getAddressBalance(itemAccount.address).toLong()
    }

    /**
     * Get total BTC balance from [AccountKey].
     *
     * @param accountKey [AccountKey]
     * @return A value as a [CryptoValue] that matches the [CryptoCurrency] and specifications of the [accountKey].
     */
    fun balance(accountKey: AccountKey): CryptoValue =
        accountKey.currency.toBalanceReporter().run {
            return when (accountKey) {
                is AccountKey.EntireWallet -> entireBalance()
                is AccountKey.WatchOnly -> watchOnlyBalance()
                is AccountKey.OnlyImported -> importedAddressBalance()
                is AccountKey.SingleAddress -> addressBalance(accountKey.address)
            }
        }

    /**
     * Get total BCH balance from [ItemAccount].
     *
     * @param itemAccount [ItemAccount]
     * @return A BCH value as a long.
     */
    fun getBchBalance(itemAccount: ItemAccount): Long = when (itemAccount.type) {
        ItemAccount.TYPE.ALL_ACCOUNTS_AND_LEGACY -> bchDataManager.getWalletBalance().toLong()
        ItemAccount.TYPE.ALL_LEGACY -> bchDataManager.getImportedAddressBalance().toLong()
        ItemAccount.TYPE.SINGLE_ACCOUNT -> bchDataManager.getAddressBalance(itemAccount.address!!).toLong()
    }

    /**
     * Get a specific [Displayable] from a hash
     *
     * @param transactionHash The hash of the Tx to be returned
     * @return An Observable object wrapping a Tx. Will call onError if not found with a
     * NullPointerException
     */
    fun getTxFromHash(transactionHash: String): Single<Displayable> =
        Observable.fromIterable(getTransactionList())
            .filter { it.hash == transactionHash }
            .firstOrError()

    /**
     * Returns a [HashMap] where a [Displayable] hash is used as a key against
     * the confirmation number. This is for displaying the confirmation number in the Contacts page.
     * Please note that this is deliberately not cleared when switching accounts.
     */
    fun getTxConfirmationsMap(): HashMap<String, Int> = transactionListStore.txConfirmationsMap

    private fun insertTransactionList(txList: MutableList<Displayable>) {
        val pendingTxs = getRemainingPendingTransactionList(txList)
        clearTransactionList()
        txList.addAll(pendingTxs)
        transactionListStore.insertTransactions(txList)
    }

    /**
     * Gets list of transactions that have been published but delivery has not yet been confirmed.
     */
    private fun getRemainingPendingTransactionList(newlyFetchedTxs: List<Displayable>): List<Displayable> {
        val pendingMap = HashMap<String, Displayable>()
        transactionListStore.list
            .filter { it.isPending }
            .forEach { pendingMap[it.hash] = it }

        if (!pendingMap.isEmpty()) {
            filterProcessed(newlyFetchedTxs, pendingMap)
        }

        return ArrayList(pendingMap.values)
    }

    private fun filterProcessed(
        newlyFetchedTxs: List<Displayable>,
        pendingMap: HashMap<String, Displayable>
    ) {
        newlyFetchedTxs.filter { pendingMap.containsKey(it.hash) }
            .forEach { pendingMap.remove(it.hash) }
    }

    private fun getAllTransactionsObservable(
        limit: Int,
        offset: Int
    ): Observable<List<Displayable>> =
        Observable.fromCallable {
            payloadManager.getAllTransactions(limit, offset)
                .map { BtcDisplayable(it) }
        }

    private fun getLegacyObservable(limit: Int, offset: Int): Observable<List<Displayable>> =
        Observable.fromCallable {
            payloadManager.getImportedAddressesTransactions(limit, offset)
                .map { BtcDisplayable(it) }
        }

    private fun getAccountObservable(
        address: String,
        limit: Int,
        offset: Int
    ): Observable<List<Displayable>> =
        Observable.fromCallable {
            payloadManager.getAccountTransactions(address, limit, offset)
                .map { BtcDisplayable(it) }
        }

    private fun getEthereumObservable(): Observable<List<Displayable>> =
        ethDataManager.getLatestBlock()
            .flatMap { latestBlock ->
                ethDataManager.getEthTransactions()
                    .map {
                        EthDisplayable(
                            ethDataManager.getEthResponseModel()!!,
                            it,
                            latestBlock.blockHeight
                        )
                    }.toList()
                    .toObservable()
            }

    private fun getBchAllTransactionsObservable(
        limit: Int,
        offset: Int
    ): Observable<List<Displayable>> =
        bchDataManager.getWalletTransactions(limit, offset)
            .mapList { BchDisplayable(it) }

    private fun getBchLegacyObservable(limit: Int, offset: Int): Observable<List<Displayable>> =
        bchDataManager.getImportedAddressTransactions(limit, offset)
            .mapList { BchDisplayable(it) }

    private fun getBchAccountObservable(
        address: String,
        limit: Int,
        offset: Int
    ): Observable<List<Displayable>> =
        bchDataManager.getAddressTransactions(address, limit, offset)
            .mapList { BchDisplayable(it) }

    private fun <T, R> Observable<List<T>>.mapList(func: (T) -> R): Observable<List<R>> {
        return flatMapIterable { list ->
            list.map { func(it) }
        }.toList().toObservable()
    }

    private fun CryptoCurrency.toBalanceReporter(): BalanceReporter {
        return when (this) {
            CryptoCurrency.BTC -> payloadManager.toBalanceReporter()
            CryptoCurrency.BCH -> bchDataManager.toBalanceReporter()
            CryptoCurrency.ETHER -> TODO("not implemented")
        }
    }
}

package com.airbnb.mvrx.mock


import android.content.Context
import android.content.IntentFilter
import android.util.Log
import com.airbnb.mvrx.BaseMvRxViewModel
import com.airbnb.mvrx.MvRx
import com.airbnb.mvrx.MvRxState
import com.airbnb.mvrx.MvRxStateStore
import com.airbnb.mvrx.MvRxViewModelConfig
import com.airbnb.mvrx.MvRxViewModelConfigFactory
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.RealMvRxStateStore
import com.airbnb.mvrx.mock.printer.MvRxMockPrinter
import com.airbnb.mvrx.mock.printer.ViewModelStatePrinter
import java.util.LinkedList

class MockableMvRxViewModelConfig<S : Any>(
    private val mockableStateStore: MockableMvRxStateStore<S>,
    private val initialMockBehavior: MockBehavior
) : MvRxViewModelConfig<S>(debugMode = true, stateStore = mockableStateStore) {


    val currentMockBehavior: MockBehavior
        get() = mockBehaviorOverrides.peek() ?: initialMockBehavior
    private val mockBehaviorOverrides = LinkedList<MockBehavior>()

    fun pushBehaviorOverride(mockBehavior: MockBehavior) {
        mockBehaviorOverrides.push(mockBehavior)
        updateStateStore()
    }

    private fun updateStateStore() {
        val currentBehavior = currentMockBehavior
        mockableStateStore.mockBehavior = currentBehavior
    }

    fun popBehaviorOverride() {
        // It is ok if this list is empty, as the config may have been created after others,
        // so it may not have an override set while other active configs may have one.
        mockBehaviorOverrides.pollFirst()
        updateStateStore()
    }

    companion object {
        /**
         * Allows access to the mock configuration of a ViewModel.
         *
         * The config is not directly exposed to discourage use. It should only be carefully
         * used in testing frameworks.
         *
         * This assumes the viewmodel was created with a mock config applied, and fails otherwise.
         */
        fun <S : MvRxState> access(viewModel: BaseMvRxViewModel<S>): MockableMvRxViewModelConfig<S> {
            return viewModel.config as MockableMvRxViewModelConfig
        }
    }

    override fun <S : MvRxState> onExecute(
        viewModel: BaseMvRxViewModel<S>
    ): BlockExecutions {
        val blockExecutions = currentMockBehavior.blockExecutions

        if (blockExecutions != BlockExecutions.No) {
            viewModel.reportExecuteCallToInteractionTest()
        }

        return blockExecutions
    }
}

/**
 * Different types of mock setups that can be provided.
 *
 * When a mocked view model is created, "existing" view model expectations are ignored, and the viewmodel is created from
 * mocked state instead of throwing an exception.
 *
 * However, it is up to the caller to make sure that a ViewModel of the expected type doesn't already exist in the view model store, otherwise
 * the framework will use that one instead of allowing us to create a new mocked one, so make sure to clear the store of any previously
 * created viewmodels first.
 */
data class MockBehavior(
    val initialState: InitialState = InitialState.None,
    val blockExecutions: MvRxViewModelConfig.BlockExecutions = MvRxViewModelConfig.BlockExecutions.No,
    val stateStoreBehavior: StateStoreBehavior = StateStoreBehavior.Normal
) {
    /** Describes how a custom mocked state is applied to initialize a new ViewModel. */
    enum class InitialState {
        /** No mocked state is applied. The ViewModel initializes itself through normal means. */
        None,
        /**
         * Generally uses the same behavior as [None], however, if an 'existingViewModel' is accessed and no previous instance exists
         * this will take the default mock state off of the Fragment for that ViewModel and initialize the ViewModel with that state.
         *
         * This is useful when we want a mocked screen to be able to open non mocked screens (in a test), and would otherwise crash
         * if a newly opened screen uses 'existingViewModel' and the viewmodel doesn't actually exist (because we came from a mocked screen
         * that doesn't represent normal screen flow).
         */
        ForceMockExistingViewModel,
        /**
         * Initial view model state is taken from [MockStateHolder] and used in ViewModel creation,
         * but that mocked state may be overridden either in the [MvRxViewModelFactory] or constructor of the ViewModel.
         */
        Partial,
        /**
         * Initial view model state is taken from [MockStateHolder] and used in ViewModel creation.
         * Additionally, any changes to state during ViewModel initialization are forcibly overridden by the mocked state.
         */
        Full
    }

    enum class StateStoreBehavior {
        Normal,
        Scriptable,
        /**
         * When toggled to use the real state store (instead of the scriptable store), this controls whether to use
         * a synchronous version of the state store or the original MvRx state store that operates asynchronously.
         * The immediate, synchronous store can help for testing state changes.
         */
        Synchronous
    }
}

open class MockMvRxViewModelConfigFactory(val context: Context?) : MvRxViewModelConfigFactory(
    debugMode = true
) {

    private val mockConfigs = mutableMapOf<MvRxStateStore<*>, MockableMvRxViewModelConfig<*>>()

    /**
     * Determines what sort of mocked state store is created when [provideConfig] is called.
     * This can be changed via [withMockBehavior] to affect behavior when creating a new Fragment.
     *
     * A value can also be set directly here if you want to change the global default.
     */
    var mockBehavior: MockBehavior = MockBehavior(
        initialState = MockBehavior.InitialState.None,
        blockExecutions = MvRxViewModelConfig.BlockExecutions.No,
        stateStoreBehavior = MockBehavior.StateStoreBehavior.Normal
    )

    /**
     * Any view models created within the [block] will be given a viewmodel store that was created according to the rules of the given mock behavior.
     * The default value may have been set by a wrapping call to [withMockBehavior] (ie if multiple calls to withMockBehavior
     * are nested the most recent call can change the behavior of the outer call)
     *
     * After the block has executed, the previous setting for mockBehavior will be used again.
     *
     * @param mockBehavior Null to have ViewModels created with a [RealMvRxStateStore]. Non null to create ViewModels with a [MockableMvRxStateStore]
     * If not null, the [MockableMvRxStateStore] will be created with the options declared in the [MockBehavior]
     */
    fun <R> withMockBehavior(
        mockBehavior: MockBehavior = this.mockBehavior,
        block: () -> R
    ): R {
        // This function is not inlined so that the caller cannot return early,
        // which would skip setting back the original value!

        // Nesting this call is tricky because an inner call may change the mock behavior that an outer call originally set.
        // To avoid potential bugs because of that we restore the original setting when leaving the block
        val originalSetting = this.mockBehavior

        this.mockBehavior = mockBehavior
        val result = block()
        this.mockBehavior = originalSetting

        return result
    }

    private fun onMockStoreDisposed(store: MockableStateStore<*>) {
        mockConfigs.remove(store)
    }

    override fun <S : MvRxState> buildConfig(
        viewModel: BaseMvRxViewModel<S>,
        initialState: S
    ): MvRxViewModelConfig<S> {
        val mockBehavior = mockBehavior

        val stateStore = MockableMvRxStateStore(
            initialState = initialState,
            mockBehavior = mockBehavior
        )

        return MockableMvRxViewModelConfig(
            stateStore,
            mockBehavior
        ).also { config ->
            // Since this is an easy place to hook into all viewmodel creation and clearing
            // we use it as an opportunity to register the mock printer on all view models.
            // This lets us capture singleton viewmodels as well.
            val viewModelStatePrinter = ViewModelStatePrinter(viewModel)
            context?.let { viewModelStatePrinter.register(it) }

            mockConfigs[stateStore] = config
            stateStore.addOnDisposeListener { stateStore ->
                context?.let { viewModelStatePrinter.unregister(it) }
                onMockStoreDisposed(stateStore)
            }
        }
    }

    /**
     * Changes the current [MockBehavior] of all running ViewModels, if they were created when
     * [mockBehavior] was non null. This forces the mock behavior of to this new value.
     *
     * This should be followed later by a corresponding call to [popMockBehaviorOverride] in order
     * to revert the mock behavior to its original value.
     */
    fun pushMockBehaviorOverride(mockBehavior: MockBehavior) {
        mockConfigs.values.forEach { it.pushBehaviorOverride(mockBehavior) }
    }

    fun popMockBehaviorOverride() {
        mockConfigs.values.forEach { it.popBehaviorOverride() }
    }
}


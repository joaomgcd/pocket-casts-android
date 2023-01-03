package au.com.shiftyjelly.pocketcasts.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.LiveDataReactiveStreams
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.to.FolderItem
import au.com.shiftyjelly.pocketcasts.models.to.SignInState
import au.com.shiftyjelly.pocketcasts.models.type.PodcastsSortType
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.podcast.FolderManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.user.UserManager
import au.com.shiftyjelly.pocketcasts.servers.ServerManager
import au.com.shiftyjelly.pocketcasts.servers.discover.PodcastSearch
import com.jakewharton.rxrelay2.BehaviorRelay
import io.reactivex.BackpressureStrategy
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.Observables
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class SearchHandler @Inject constructor(
    val serverManager: ServerManager,
    val podcastManager: PodcastManager,
    val userManager: UserManager,
    val settings: Settings,
    folderManager: FolderManager
) {
    private val searchQuery = BehaviorRelay.create<Query>().apply {
        accept(Query(""))
    }

    private val loadingObservable = BehaviorRelay.create<Boolean>().apply {
        accept(false)
    }
    private val onlySearchRemoteObservable = BehaviorRelay.create<Boolean>().apply {
        accept(false)
    }
    private val signInStateObservable = userManager.getSignInState().startWith(SignInState.SignedOut()).toObservable()

    private val localResults = Observable
        .combineLatest(searchQuery, onlySearchRemoteObservable, signInStateObservable) { searchQuery, onlySearchRemoteObservable, signInState ->
            Pair(if (onlySearchRemoteObservable) "" else searchQuery.string, signInState)
        }
        .subscribeOn(Schedulers.io())
        .switchMap { (query, signInState) ->
            if (query.isEmpty()) {
                Observable.just(emptyList())
            } else {
                // search folders
                val folderSearch =
                    if (signInState.isSignedInAsPlus) {
                        // only show folders if the user has Plus
                        folderManager.findFoldersSingle()
                            .subscribeOn(Schedulers.io())
                            .flatMapObservable { Observable.fromIterable(it) }
                            .filter { it.name.contains(query, ignoreCase = true) }
                            .switchMapSingle { folder ->
                                podcastManager
                                    .findPodcastsInFolderSingle(folderUuid = folder.uuid)
                                    .map { podcasts -> FolderItem.Folder(folder = folder, podcasts = podcasts) }
                            }
                            .toList()
                    } else {
                        Single.just(emptyList())
                    }

                // search podcasts
                val podcastSearch = podcastManager.findSubscribedRx()
                    .subscribeOn(Schedulers.io())
                    .flatMapObservable { Observable.fromIterable(it) }
                    .filter { it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true) }
                    .map { podcast ->
                        podcast.isSubscribed = true
                        FolderItem.Podcast(podcast)
                    }
                    .toList()

                podcastSearch
                    .zipWith(folderSearch) { podcasts, folders ->
                        (podcasts + folders).sortedBy { PodcastsSortType.cleanStringForSort(it.title) }
                    }
                    .toObservable()
            }
        }

    private val subscribedPodcastUuids = podcastManager
        .findSubscribedRx()
        .subscribeOn(Schedulers.io())
        .toObservable()
        .map { podcasts -> podcasts.map(Podcast::uuid).toHashSet() }

    private val serverSearchResults = searchQuery
        .subscribeOn(Schedulers.io())
        .map { it.copy(string = it.string.trim()) }
        .debounce {
            val debounceQuery = it.string.isNotEmpty() && !it.immediate
            if (debounceQuery) {
                val debounceMs = settings.getPodcastSearchDebounceMs()
                Observable.timer(debounceMs, TimeUnit.MILLISECONDS)
            } else {
                Observable.empty()
            }
        }
        .map { it.string }
        .switchMap {
            if (it.length <= 1) {
                Observable.just(PodcastSearch())
            } else {
                loadingObservable.accept(true)
                serverManager.searchForPodcastsRx(it).toObservable()
                    .onErrorReturn { exception ->
                        PodcastSearch(error = exception)
                    }
            }
        }
        .doOnNext { loadingObservable.accept(false) }

    private val searchFlowable = Observables.combineLatest(searchQuery, subscribedPodcastUuids, localResults, serverSearchResults, loadingObservable) { searchTerm, subscribedPodcastUuids, localResults, serverSearchResults, loading ->
        if (searchTerm.string.isBlank()) {
            SearchState.Results(list = emptyList(), loading = loading, error = null)
        } else {
            // set if the podcast is subscribed so we can show a tick
            val serverResults = serverSearchResults.searchResults.map { podcast -> FolderItem.Podcast(podcast) }
            serverResults.forEach {
                if (subscribedPodcastUuids.contains(it.podcast.uuid)) {
                    it.podcast.isSubscribed = true
                }
            }
            val searchResults = (localResults + serverResults).distinctBy { it.uuid }

            if (serverSearchResults.searchTerm.isEmpty() || searchResults.isNotEmpty() || serverSearchResults.error != null) {
                SearchState.Results(list = searchResults, loading = loading, error = serverSearchResults.error)
            } else {
                SearchState.NoResults
            }
        }
    }
        .doOnError { Timber.e(it) }
        .onErrorReturn { exception -> SearchState.Results(list = emptyList(), loading = false, error = exception) }
        .observeOn(AndroidSchedulers.mainThread())
        .toFlowable(BackpressureStrategy.LATEST)

    val searchResults: LiveData<SearchState> = LiveDataReactiveStreams.fromPublisher(searchFlowable)
    val loading: LiveData<Boolean> = LiveDataReactiveStreams.fromPublisher(loadingObservable.toFlowable(BackpressureStrategy.LATEST))

    fun updateSearchQuery(query: String, immediate: Boolean = false) {
        searchQuery.accept(Query(query, immediate))
    }

    fun setOnlySearchRemote(remote: Boolean) {
        onlySearchRemoteObservable.accept(remote)
    }

    private data class Query(val string: String, val immediate: Boolean = false)
}

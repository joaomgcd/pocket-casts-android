package au.com.shiftyjelly.pocketcasts.filters

import android.animation.LayoutTransition
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import au.com.shiftyjelly.pocketcasts.filters.databinding.FragmentFilterBinding
import au.com.shiftyjelly.pocketcasts.localization.extensions.getStringPluralPodcasts
import au.com.shiftyjelly.pocketcasts.models.entity.Episode
import au.com.shiftyjelly.pocketcasts.models.entity.Playable
import au.com.shiftyjelly.pocketcasts.models.entity.Playlist
import au.com.shiftyjelly.pocketcasts.podcasts.view.components.PlayButton
import au.com.shiftyjelly.pocketcasts.podcasts.view.episode.EpisodeFragment
import au.com.shiftyjelly.pocketcasts.podcasts.view.podcast.EpisodeListAdapter
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.chromecast.CastManager
import au.com.shiftyjelly.pocketcasts.repositories.download.DownloadManager
import au.com.shiftyjelly.pocketcasts.repositories.images.PodcastImageLoader
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextQueue
import au.com.shiftyjelly.pocketcasts.ui.extensions.getColor
import au.com.shiftyjelly.pocketcasts.ui.extensions.getStringForDuration
import au.com.shiftyjelly.pocketcasts.ui.helper.FragmentHostListener
import au.com.shiftyjelly.pocketcasts.ui.helper.StatusBarColor
import au.com.shiftyjelly.pocketcasts.ui.images.PodcastImageLoaderThemed
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import au.com.shiftyjelly.pocketcasts.ui.theme.ThemeColor
import au.com.shiftyjelly.pocketcasts.utils.AnalyticsHelper
import au.com.shiftyjelly.pocketcasts.utils.extensions.dpToPx
import au.com.shiftyjelly.pocketcasts.views.dialog.ConfirmationDialog
import au.com.shiftyjelly.pocketcasts.views.dialog.OptionsDialog
import au.com.shiftyjelly.pocketcasts.views.fragments.BaseFragment
import au.com.shiftyjelly.pocketcasts.views.helper.EpisodeItemTouchHelper
import au.com.shiftyjelly.pocketcasts.views.helper.NavigationIcon.BackArrow
import au.com.shiftyjelly.pocketcasts.views.helper.ToolbarColors
import au.com.shiftyjelly.pocketcasts.views.multiselect.MultiSelectHelper
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import au.com.shiftyjelly.pocketcasts.images.R as IR
import au.com.shiftyjelly.pocketcasts.localization.R as LR

private const val ARG_PLAYLIST_UUID = "playlist_uuid"
private const val ARG_PLAYLIST_TITLE = "playlist_title"
private const val ARG_FILTER_IS_NEW = "playlist_new"
private const val ARG_COLOR = "color"
private const val STATE_LAYOUT_MANAGER = "layout_manager"

@AndroidEntryPoint
class FilterEpisodeListFragment : BaseFragment() {
    companion object {
        fun newInstance(playlist: Playlist, isNewFilter: Boolean, context: Context): FilterEpisodeListFragment {
            val fragment = FilterEpisodeListFragment()
            val bundle = Bundle()
            bundle.putString(ARG_PLAYLIST_UUID, playlist.uuid)
            bundle.putString(ARG_PLAYLIST_TITLE, playlist.title)
            bundle.putBoolean(ARG_FILTER_IS_NEW, isNewFilter)
            bundle.putInt(ARG_COLOR, playlist.getColor(context))
            fragment.arguments = bundle
            return fragment
        }
    }

    private val viewModel by viewModels<FilterEpisodeListViewModel>()

    @Inject lateinit var downloadManager: DownloadManager
    @Inject lateinit var playbackManager: PlaybackManager
    @Inject lateinit var playButtonListener: PlayButton.OnClickListener
    @Inject lateinit var settings: Settings
    @Inject lateinit var castManager: CastManager
    @Inject lateinit var upNextQueue: UpNextQueue
    @Inject lateinit var multiSelectHelper: MultiSelectHelper

    private lateinit var imageLoader: PodcastImageLoader

    private lateinit var adapter: EpisodeListAdapter
    private var showingFilterOptionsBeforeMultiSelect: Boolean = false
    private var multiSelectLoaded: Boolean = false
    private var listSavedState: Parcelable? = null
    private var showingFilterOptionsBeforeModal: Boolean = false
    private var binding: FragmentFilterBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        listSavedState = savedInstanceState?.getParcelable(STATE_LAYOUT_MANAGER)
        showingFilterOptionsBeforeModal = arguments?.getBoolean(ARG_FILTER_IS_NEW) ?: false

        AnalyticsHelper.openedFilter()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        imageLoader = PodcastImageLoaderThemed(context).apply {
            radiusPx = 4.dpToPx(context)
        }.smallPlaceholder()

        adapter = EpisodeListAdapter(downloadManager, playbackManager, upNextQueue, settings, this::onRowClick, playButtonListener, imageLoader, multiSelectHelper)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(STATE_LAYOUT_MANAGER, binding?.recyclerView?.layoutManager?.onSaveInstanceState())
    }

    override fun onPause() {
        super.onPause()

        val binding = binding ?: return
        binding.toolbar.menu.close()
        showingFilterOptionsBeforeModal = binding.layoutFilterOptions.isVisible
        listSavedState = binding.recyclerView.layoutManager?.onSaveInstanceState()
        binding.recyclerView.adapter = null
        multiSelectHelper.isMultiSelecting = false
    }

    override fun onResume() {
        super.onResume()

        val binding = binding ?: return
        val recyclerView = binding.recyclerView
        recyclerView.adapter = adapter
        listSavedState?.let { recyclerView.layoutManager?.onRestoreInstanceState(it) }
        setShowFilterOptions(showingFilterOptionsBeforeModal)
    }

    override fun onDestroyView() {
        binding?.recyclerView?.adapter = null
        super.onDestroyView()

        binding = null

        activity?.let {
            statusBarColor = StatusBarColor.Light
            updateStatusBar()
        }
    }

    private fun onRowClick(episode: Playable) {
        if (episode is Episode) {
            val fragment = EpisodeFragment.newInstance(episode)
            fragment.show(parentFragmentManager, "episode_card")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentFilterBinding.inflate(inflater, container, false)
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.setup(arguments?.getString(ARG_PLAYLIST_UUID)!!)

        val binding = binding ?: return
        val toolbar = binding.toolbar
        val recyclerView = binding.recyclerView
        val emptyLayout = binding.emptyLayout

        setupToolbarAndStatusBar(
            toolbar = toolbar,
            title = arguments?.getString(ARG_PLAYLIST_TITLE),
            menu = R.menu.menu_filter,
            setupChromeCast = true,
            navigationIcon = BackArrow,
            toolbarColors = null
        )

        toolbar.setNavigationOnClickListener { (activity as AppCompatActivity).onBackPressed() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item?.itemId) {
                R.id.menu_delete -> {
                    showDeleteConfirmation()
                    true
                }
                R.id.menu_playall -> {
                    val firstEpisode = viewModel.episodesList.value?.firstOrNull() ?: return@setOnMenuItemClickListener true
                    playAllFromHereWarning(firstEpisode, isFirstEpisode = true)
                    true
                }
                R.id.menu_sortby -> {
                    showSortOptions()
                    true
                }
                R.id.menu_options -> {
                    showFilterSettings()
                    true
                }
                R.id.menu_downloadall -> {
                    downloadAll()
                    true
                }
                else -> false
            }
        }

        val linearLayoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        recyclerView.layoutManager = linearLayoutManager
        (recyclerView.itemAnimator as SimpleItemAnimator).changeDuration = 0

        recyclerView.adapter = adapter

        viewModel.episodesList.observe(viewLifecycleOwner) {
            adapter.submitList(it)

            emptyLayout.isVisible = it.isEmpty()
        }

        binding.filterOptionsFrame.layoutTransition.enableTransitionType(LayoutTransition.CHANGING)

        viewModel.playlistDeleted.observe(viewLifecycleOwner) { deleted ->
            if (deleted) {
                activity?.onBackPressed()
            }
        }

        // Load color from bundle first
        if (arguments?.containsKey(ARG_COLOR) == true) {
            val color = arguments?.getInt(ARG_COLOR) ?: 0
            updateUIColors(color)
        }

        viewModel.playlist.observe(viewLifecycleOwner) { playlist ->
            toolbar.title = playlist.title

            val color = playlist.getColor(context)

            adapter.tintColor = color
            updateUIColors(color)

            val chipPodcasts = binding.chipPodcasts
            if (playlist.allPodcasts) {
                chipPodcasts.text = getString(LR.string.filters_chip_all_your_podcasts)
                chipPodcasts.setInactiveColors(theme.activeTheme, color)
            } else {
                chipPodcasts.text =
                    resources.getStringPluralPodcasts(playlist.podcastUuidList.count())
                chipPodcasts.setActiveColors(theme.activeTheme, color)
            }
            chipPodcasts.setOnClickListener {
                (activity as FragmentHostListener).showModal(
                    PodcastOptionsFragment.newInstance(
                        playlist
                    )
                )
            }

            val chipEpisodes = binding.chipEpisodes
            val episodeOptions = playlist.episodeOptionStringIds.map { resources.getString(it) }
            if ((playlist.unplayed && playlist.partiallyPlayed && playlist.finished) || episodeOptions.isEmpty()) {
                chipEpisodes.text = getString(LR.string.filters_chip_episode_status)
                chipEpisodes.setInactiveColors(theme.activeTheme, color)
            } else {
                when {
                    episodeOptions.count() > 1 -> chipEpisodes.text = episodeOptions.joinToString()
                    episodeOptions.isNotEmpty() -> chipEpisodes.text = episodeOptions.first()
                    else -> chipEpisodes.text = getString(LR.string.filters_chip_episode_status)
                }
                chipEpisodes.setActiveColors(theme.activeTheme, color)
            }
            chipEpisodes.setOnClickListener {
                (activity as FragmentHostListener).showModal(
                    EpisodeOptionsFragment.newInstance(
                        playlist
                    )
                )
            }

            val chipTime = binding.chipTime
            chipTime.setText(playlist.stringForFilterHours)
            if (playlist.filterHours == 0) {
                chipTime.setInactiveColors(theme.activeTheme, color)
            } else {
                chipTime.setActiveColors(theme.activeTheme, color)
            }
            chipTime.setOnClickListener {
                (activity as FragmentHostListener).showModal(
                    TimeOptionsFragment.newInstance(
                        playlist,
                        TimeOptionsFragment.OptionsType.Time
                    )
                )
            }

            val chipDuration = binding.chipDuration
            chipDuration.text = playlist.getStringForDuration(context)
            if (playlist.filterDuration) {
                chipDuration.setActiveColors(theme.activeTheme, color)
            } else {
                chipDuration.setInactiveColors(theme.activeTheme, color)
            }
            chipDuration.setOnClickListener {
                (activity as FragmentHostListener).showModal(
                    DurationOptionsFragment.newInstance(
                        playlist
                    )
                )
            }

            val chipDownload = binding.chipDownload
            val downloadOptions = playlist.downloadOptionStrings
            if (downloadOptions.isEmpty()) {
                chipDownload.setInactiveColors(theme.activeTheme, color)
                chipDownload.text = getString(LR.string.filters_chip_download_status)
            } else {
                chipDownload.text = downloadOptions.joinToString { getString(it) }
                chipDownload.setActiveColors(theme.activeTheme, color)
            }
            chipDownload.setOnClickListener {
                (activity as FragmentHostListener).showModal(
                    TimeOptionsFragment.newInstance(
                        playlist,
                        TimeOptionsFragment.OptionsType.Downloaded
                    )
                )
            }

            val chipAudioVideo = binding.chipAudioVideo
            val audioOptions = playlist.audioOptionStrings
            if (audioOptions.isEmpty()) {
                chipAudioVideo.setInactiveColors(theme.activeTheme, color)
                chipAudioVideo.text = getString(LR.string.filters_chip_media_type)
            } else {
                chipAudioVideo.text = audioOptions.joinToString { getString(it) }
                chipAudioVideo.setActiveColors(theme.activeTheme, color)
            }
            chipAudioVideo.setOnClickListener {
                (activity as FragmentHostListener).showModal(
                    TimeOptionsFragment.newInstance(
                        playlist,
                        TimeOptionsFragment.OptionsType.AudioVideo
                    )
                )
            }

            val chipStarred = binding.chipStarred
            val starred = playlist.starred
            chipStarred.text = getString(LR.string.filters_chip_starred)
            if (starred) {
                chipStarred.setActiveColors(theme.activeTheme, color)
            } else {
                chipStarred.setInactiveColors(theme.activeTheme, color)
            }
            chipStarred.setOnClickListener { viewModel.starredChipTapped() }
        }

        val layoutFilterOptions = binding.layoutFilterOptions
        if (arguments?.getBoolean(ARG_FILTER_IS_NEW) == false) { // Default to closed options unless the filter is new
            layoutFilterOptions.isVisible = false
        } else {
            setShowFilterOptions(true)
        }

        val clickListener: (View) -> Unit = {
            setShowFilterOptions(show = !layoutFilterOptions.isVisible)
        }
        binding.btnChevron.setOnClickListener(clickListener)
        toolbar.setOnClickListener(clickListener)

        val itemTouchHelper = EpisodeItemTouchHelper(this::episodeSwipedRightItem1, this::episodeSwipedRightItem2, viewModel::episodeSwiped)
        itemTouchHelper.attachToRecyclerView(recyclerView)

        val multiSelectToolbar = binding.multiSelectToolbar
        multiSelectHelper.isMultiSelectingLive.observe(
            viewLifecycleOwner,
            Observer {
                if (!multiSelectLoaded) {
                    multiSelectLoaded = true
                    return@Observer // Skip the initial value or else it will always hide the filter controls on load
                }

                if (!multiSelectToolbar.isVisible) {
                    showingFilterOptionsBeforeMultiSelect = layoutFilterOptions.isVisible
                    setShowFilterOptions(false)
                } else {
                    setShowFilterOptions(showingFilterOptionsBeforeMultiSelect)
                }
                multiSelectToolbar.isVisible = it
                toolbar.isVisible = !it

                adapter.notifyDataSetChanged()
            }
        )
        multiSelectHelper.coordinatorLayout = (activity as FragmentHostListener).snackBarView()
        multiSelectHelper.listener = object : MultiSelectHelper.Listener {
            override fun multiSelectSelectAll() {
                val episodes = viewModel.episodesList.value
                if (episodes != null) {
                    multiSelectHelper.selectAllInList(episodes)
                    adapter.notifyDataSetChanged()
                }
            }

            override fun multiSelectSelectNone() {
                val episodes = viewModel.episodesList.value
                if (episodes != null) {
                    episodes.forEach { multiSelectHelper.deselect(it) }
                    adapter.notifyDataSetChanged()
                }
            }

            override fun multiSelectSelectAllUp(episode: Playable) {
                val episodes = viewModel.episodesList.value
                if (episodes != null) {
                    val startIndex = episodes.indexOf(episode)
                    if (startIndex > -1) {
                        multiSelectHelper.selectAllInList(episodes.subList(0, startIndex + 1))
                    }

                    adapter.notifyDataSetChanged()
                }
            }

            override fun multiSelectSelectAllDown(episode: Playable) {
                val episodes = viewModel.episodesList.value
                if (episodes != null) {
                    val startIndex = episodes.indexOf(episode)
                    if (startIndex > -1) {
                        multiSelectHelper.selectAllInList(episodes.subList(startIndex, episodes.size))
                    }

                    adapter.notifyDataSetChanged()
                }
            }
        }
        multiSelectHelper.fragmentManager = childFragmentManager
        multiSelectToolbar.setup(viewLifecycleOwner, multiSelectHelper, menuRes = null, fragmentManager = parentFragmentManager)
    }

    private fun updateUIColors(@ColorInt color: Int) {
        val binding = binding ?: return

        val toolbar = binding.toolbar
        val colors = ToolbarColors.User(color = color, theme = theme)
        setupToolbarAndStatusBar(
            toolbar = toolbar,
            navigationIcon = BackArrow,
            toolbarColors = colors
        )

        binding.layoutFilterOptions.setBackgroundColor(colors.backgroundColor)
        binding.btnChevron.imageTintList = ColorStateList.valueOf(colors.iconColor)
    }

    fun setShowFilterOptions(show: Boolean) {
        val binding = binding ?: return

        binding.layoutFilterOptions.isVisible = show
        val angle = if (binding.layoutFilterOptions.isVisible) 180f else 0f
        binding.btnChevron.animate().rotation(angle).start()
    }

    fun showSortOptions() {
        viewModel.playlist.value?.let {
            val dialog = OptionsDialog()
                .setTitle(getString(LR.string.sort_by))
                .addCheckedOption(
                    titleId = LR.string.episode_sort_newest_to_oldest,
                    click = { viewModel.changeSort(Playlist.PLAYLIST_SORT_NEWEST_TO_OLDEST) },
                    checked = (it.sortId == Playlist.PLAYLIST_SORT_NEWEST_TO_OLDEST)
                )
                .addCheckedOption(
                    titleId = LR.string.episode_sort_oldest_to_newest,
                    click = { viewModel.changeSort(Playlist.PLAYLIST_SORT_OLDEST_TO_NEWEST) },
                    checked = (it.sortId == Playlist.PLAYLIST_SORT_OLDEST_TO_NEWEST)
                )
                .addCheckedOption(
                    titleId = LR.string.episode_sort_short_to_long,
                    click = { viewModel.changeSort(Playlist.PLAYLIST_SORT_SHORTEST_TO_LONGEST) },
                    checked = (it.sortId == Playlist.PLAYLIST_SORT_SHORTEST_TO_LONGEST)
                )
                .addCheckedOption(
                    titleId = LR.string.episode_sort_long_to_short,
                    click = { viewModel.changeSort(Playlist.PLAYLIST_SORT_LONGEST_TO_SHORTEST) },
                    checked = (it.sortId == Playlist.PLAYLIST_SORT_LONGEST_TO_SHORTEST)
                )
            dialog.show(parentFragmentManager, "sort_options")
        }
    }

    fun showFilterSettings() {
        viewModel.playlist.value?.let {
            val fragment = CreateFilterFragment.newInstance(CreateFilterFragment.Mode.Edit(it))
            (activity as? FragmentHostListener)?.addFragment(fragment)
        }
    }

    fun showDeleteConfirmation() {
        ConfirmationDialog().setTitle(getString(LR.string.are_you_sure))
            .setIconId(IR.drawable.ic_filters)
            .setSummary(getString(LR.string.filters_warning_delete_summary))
            .setButtonType(ConfirmationDialog.ButtonType.Danger(getString(LR.string.filters_warning_delete_button)))
            .setOnConfirm {
                viewModel.deletePlaylist()
                activity?.onBackPressed()
            }
            .show(childFragmentManager, "confirm")
    }

    private fun episodeSwipedRightItem1(episode: Playable, index: Int) {
        when (settings.getUpNextSwipeAction()) {
            Settings.UpNextAction.PLAY_NEXT -> viewModel.episodeSwipeUpNext(episode)
            Settings.UpNextAction.PLAY_LAST -> viewModel.episodeSwipeUpLast(episode)
        }
        adapter.notifyItemChanged(index)
    }

    private fun episodeSwipedRightItem2(episode: Playable, index: Int) {
        when (settings.getUpNextSwipeAction()) {
            Settings.UpNextAction.PLAY_NEXT -> viewModel.episodeSwipeUpLast(episode)
            Settings.UpNextAction.PLAY_LAST -> viewModel.episodeSwipeUpNext(episode)
        }
        adapter.notifyItemChanged(index)
    }

    private fun downloadAll() {
        val episodeCount = (viewModel.episodesList.value ?: emptyList()).count()
        if (episodeCount < 5) {
            viewModel.downloadAll()
        } else if (episodeCount in 5..FilterEpisodeListViewModel.MAX_DOWNLOAD_ALL) {
            val dialog = ConfirmationDialog()
                .setButtonType(ConfirmationDialog.ButtonType.Normal(getString(LR.string.download_warning_button, episodeCount)))
                .setIconId(IR.drawable.ic_download)
                .setTitle(getString(LR.string.download_warning_title))
                .setOnConfirm { viewModel.downloadAll() }
            dialog.show(parentFragmentManager, "download_confirm")
        } else {
            val dialog = ConfirmationDialog()
                .setButtonType(ConfirmationDialog.ButtonType.Normal(getString(LR.string.download_warning_button, FilterEpisodeListViewModel.MAX_DOWNLOAD_ALL)))
                .setIconId(IR.drawable.ic_download)
                .setTitle(getString(LR.string.download_warning_title))
                .setSummary(getString(LR.string.download_warning_limit_summary, FilterEpisodeListViewModel.MAX_DOWNLOAD_ALL))
                .setOnConfirm { viewModel.downloadAll() }
            dialog.show(parentFragmentManager, "download_confirm")
        }
    }

    private fun playAllFromHereWarning(episode: Episode, isFirstEpisode: Boolean = false) {
        val count = viewModel.onFromHereCount(episode)
        if (count <= 3) {
            viewModel.onPlayAllFromHere(episode)
            return
        }

        val title = if (isFirstEpisode) getString(LR.string.filters_play_all) else getString(LR.string.play_all_from_here)
        val summary = if (isFirstEpisode) getString(LR.string.filters_play_all_summary) else getString(LR.string.filters_play_all_from_here_summary)
        val buttonString = getString(LR.string.filters_play_episodes, count)

        val dialog = ConfirmationDialog()
            .setTitle(title)
            .setSummary(summary)
            .setIconId(IR.drawable.ic_play_all)
            .setButtonType(ConfirmationDialog.ButtonType.Danger(buttonString))
            .setOnConfirm { viewModel.onPlayAllFromHere(episode) }
        dialog.show(parentFragmentManager, "confirm_play_all")
    }

    override fun onBackPressed(): Boolean {
        return if (multiSelectHelper.isMultiSelecting) {
            multiSelectHelper.isMultiSelecting = false
            true
        } else {
            super.onBackPressed()
        }
    }

    override fun getBackstackCount(): Int {
        return super.getBackstackCount() + if (multiSelectHelper.isMultiSelecting) 1 else 0
    }
}

private fun Chip.setActiveColors(theme: Theme.ThemeType, @ColorInt filterColor: Int) {
    val backgroundColor = ThemeColor.filterInteractive03(theme, filterColor)
    chipBackgroundColor = ColorStateList.valueOf(backgroundColor)
    setTextColor(ThemeColor.filterInteractive04(theme, filterColor))
    chipStrokeColor = ColorStateList.valueOf(backgroundColor)
    chipStrokeWidth = 1.dpToPx(context).toFloat()
}

private fun Chip.setInactiveColors(theme: Theme.ThemeType, @ColorInt filterColor: Int) {
    val color = ThemeColor.filterInteractive05(theme, filterColor)
    chipBackgroundColor = ColorStateList.valueOf(ThemeColor.filterUi01(theme, filterColor))
    setTextColor(color)
    chipStrokeColor = ColorStateList.valueOf(color)
    chipStrokeWidth = 1.dpToPx(context).toFloat()
}

package au.com.shiftyjelly.pocketcasts.account

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.navigation.findNavController
import au.com.shiftyjelly.pocketcasts.account.databinding.FragmentAccountBinding
import au.com.shiftyjelly.pocketcasts.account.viewmodel.AccountFragmentViewModel
import au.com.shiftyjelly.pocketcasts.account.viewmodel.CreateAccountViewModel
import au.com.shiftyjelly.pocketcasts.models.to.SignInState
import au.com.shiftyjelly.pocketcasts.ui.extensions.getThemeTintedDrawable
import au.com.shiftyjelly.pocketcasts.utils.Util
import au.com.shiftyjelly.pocketcasts.utils.observeOnce
import au.com.shiftyjelly.pocketcasts.views.fragments.BaseFragment
import dagger.hilt.android.AndroidEntryPoint
import au.com.shiftyjelly.pocketcasts.images.R as IR
import au.com.shiftyjelly.pocketcasts.localization.R as LR
import au.com.shiftyjelly.pocketcasts.ui.R as UR

@AndroidEntryPoint
class AccountFragment : BaseFragment() {
    companion object {
        fun newInstance() = AccountFragment()
    }

    private val accountViewModel: CreateAccountViewModel by activityViewModels()

    private var realBinding: FragmentAccountBinding? = null
    private val binding: FragmentAccountBinding get() = realBinding ?: throw IllegalStateException("Trying to access the binding outside of the view lifecycle.")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentAccountBinding.inflate(layoutInflater).also {
            realBinding = it
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val viewModel: AccountFragmentViewModel by viewModels()
        viewModel.signInState.observeOnce(
            viewLifecycleOwner,
            Observer {
                val binding = realBinding ?: return@Observer

                if (it is SignInState.SignedIn) {
                    binding.btnSignIn.isVisible = false
                    binding.lblSignIn.text = getString(LR.string.profile_alreadysignedin)
                    binding.lblSaveYourPodcasts.text = getString(LR.string.profile_alreadysignedindescription)
                    binding.imgCreateAccount.setup(view.context.getThemeTintedDrawable(IR.drawable.ic_alert_small, UR.attr.support_05))
                    binding.btnCreate.text = getString(LR.string.done)
                    binding.btnCreate.setOnClickListener { activity?.finish() }
                } else {
                    val res = if (accountViewModel.supporterInstance) LR.string.profile_supporter_description else LR.string.profile_save_your_podcasts
                    binding.lblSaveYourPodcasts.setText(res)
                }
            }
        )

        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.imgCreateAccount.updateLayoutParams {
                width = 0
                height = 0
            }
        }

        binding.btnClose?.setOnClickListener {
            activity?.finish()
        }

        binding.btnCreate.setOnClickListener {
            if (view.findNavController().currentDestination?.id == R.id.accountFragment) {
                if (Util.isCarUiMode(view.context) || accountViewModel.supporterInstance) { // We can't sign up to plus on cars so skip that step
                    view.findNavController().navigate(R.id.action_accountFragment_to_createEmailFragment)
                } else {
                    view.findNavController().navigate(R.id.action_accountFragment_to_createAccountFragment)
                }
            }
        }

        binding.btnSignIn.setOnClickListener {
            if (view.findNavController().currentDestination?.id == R.id.accountFragment) {
                view.findNavController().navigate(R.id.action_accountFragment_to_signInFragment)
            }
        }
    }
}

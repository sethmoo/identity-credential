package com.android.mdl.app.fragment

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.android.mdl.app.R
import com.android.mdl.app.adapter.DocumentAdapter
import com.android.mdl.app.databinding.FragmentSelectDocumentBinding
import com.android.mdl.app.document.DocumentManager
import com.android.mdl.app.transfer.TransferManager
import com.google.android.material.snackbar.Snackbar
import org.jetbrains.anko.support.v4.toast


class SelectDocumentFragment : Fragment() {
    companion object {
        private const val LOG_TAG = "SelectDocumentFragment"
    }

    private val timeInterval = 2000 // # milliseconds passed between two back presses
    private var mBackPressed: Long = 0
    private var mBinding:FragmentSelectDocumentBinding? = null
    private var mInitialPermissionCheckComplete = false

    private val appPermissions: Array<String> =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    private val missingPermissions: List<String>
        get() = appPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(
                requireContext(),
                permission
            ) != PackageManager.PERMISSION_GRANTED
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ask to press twice before leave the app
        requireActivity().onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (mBackPressed + timeInterval > System.currentTimeMillis()) {
                        requireActivity().finish()
                        return
                    } else {
                        toast(getString(R.string.toast_press_back_twice))
                    }
                    mBackPressed = System.currentTimeMillis()
                }
            })
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setHasOptionsMenu(true)

        mBinding = FragmentSelectDocumentBinding.inflate(inflater)
        val adapter = DocumentAdapter()
        mBinding!!.fragment = this
        mBinding!!.documentList.adapter = adapter

        val documentManager = DocumentManager.getInstance(requireContext())
        // Call stop presentation to finish all presentation that could be running
        val transferManager = TransferManager.getInstance(requireContext())
        transferManager.stopPresentation(
            sendSessionTerminationMessage = true,
            useTransportSpecificSessionTermination = false
        )

        adapter.submitList(documentManager.getDocuments().toMutableList())

        doPermissionsCheck()

        return mBinding!!.root
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        // Inflate the menu; this adds items to the action bar if it is present.
        inflater.inflate(R.menu.main_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.item_settings -> {
                findNavController().navigate(R.id.action_selectDocumentFragment_to_settingsFragment)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun onStartProvisioning() {
        findNavController().navigate(
            SelectDocumentFragmentDirections.actionSelectDocumentFragmentToProvisioningFragment()
        )
    }

    fun onPresentDocuments() {
        findNavController().navigate(
            SelectDocumentFragmentDirections.actionSelectDocumentFragmentToShareDocumentFragment()
        )
    }

    override fun onResume() {
        super.onResume()
        if (missingPermissions.isNotEmpty()) {
            mBinding?.btPresentDocuments?.isEnabled = false
            // Don't show the snackbar before we've given the user the initial prompt
            if (mInitialPermissionCheckComplete) {
                showMissingPermissionSnackBar(missingPermissions.first())
            }
        } else {
            mBinding?.btPresentDocuments?.isEnabled = true
        }
    }

    private fun showMissingPermissionSnackBar(permission: String) {
        if (mBinding == null) return

        Snackbar.make(
            mBinding!!.root,
            "$permission permission is required",
            Snackbar.LENGTH_INDEFINITE
        )
            .setAction("Open Settings") {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", requireContext().packageName, null)
                    )
                )
            }
            .show()
    }

    private fun doPermissionsCheck() {
        if (missingPermissions.isEmpty()) {
            mBinding!!.btPresentDocuments.isEnabled = true
        } else {
            mBinding!!.btPresentDocuments.isEnabled = false
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                permissions.entries.forEach {
                    Log.d(LOG_TAG, "permissionsLauncher ${it.key} = ${it.value}")

                    mInitialPermissionCheckComplete = true
                    mBinding!!.btPresentDocuments.isEnabled = it.value
                    if (!it.value) {
                        showMissingPermissionSnackBar(it.key)
                        return@registerForActivityResult
                    }
                }
            }.launch(missingPermissions.toTypedArray())
        }
    }
}
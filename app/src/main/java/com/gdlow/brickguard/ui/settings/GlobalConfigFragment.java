package com.gdlow.brickguard.ui.settings;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.PreferenceFragmentCompat;

import com.gdlow.brickguard.BrickGuard;
import com.gdlow.brickguard.LockActivity;
import com.gdlow.brickguard.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

public class GlobalConfigFragment extends PreferenceFragmentCompat {

    private MaterialAlertDialogBuilder materialAlertDialogBuilder;
    private View emailDialog;

    // Email report state
    private String email;
    private boolean sendReport;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.pref_settings);
        materialAlertDialogBuilder = new MaterialAlertDialogBuilder(getContext());
        findPreference("settings_email").setOnPreferenceClickListener(preference -> {
            emailDialog = LayoutInflater.from(getContext()).inflate(R.layout.email_dialog, null,
                    false);
            launchEmailDialog();
            return true;
        });
        findPreference("settings_reset_pin").setOnPreferenceClickListener(preference -> {
            startActivity(new Intent(getActivity(), LockActivity.class)
                    .putExtra(LockActivity.LOCK_SCREEN_ACTION, LockActivity.LOCK_SCREEN_ACTION_RESET));
            return true;
        });
        findPreference("settings_faq").setOnPreferenceClickListener(preference -> {
            BrickGuard.openUri("https://github.com/gdlow/brickguard#frequently-asked-questions");
            return true;
        });
        findPreference("settings_contributions").setOnPreferenceClickListener(preference -> {
            BrickGuard.openUri("https://github.com/gdlow/brickguard#contributions");
            return true;
        });
        findPreference("settings_privacy_policy").setOnPreferenceClickListener(preference -> {
            BrickGuard.openUri("https://gdlow.github.io/brickguard/about/privacy_policy.html");
            return true;
        });
        findPreference("settings_donate").setOnPreferenceClickListener(preference -> {
            BrickGuard.openUri("https://www.paypal.com/donate/?business=FRC6AV3WFYN34&item_name" +
                    "=Support+for+BrickGuard+project&currency_code=GBP");
            return true;
        });
    }

    private void launchEmailDialog() {
        // Initialize email report state
        email = BrickGuard.getPrefs().getString("brickguard_email", "nil");
        sendReport = BrickGuard.getPrefs().getBoolean("brickguard_send_report", false);

        // Email state to update
        TextView editText = emailDialog.findViewById(R.id.email_edit_text);
        TextView emailSetText = emailDialog.findViewById(R.id.email_set_text);

        // Build the alert dialog
        materialAlertDialogBuilder.setView(emailDialog)
                .setPositiveButton("Save", (dialog, which) -> {
                    updateEmailState(editText, emailSetText);
                    // Save email in shared preferences
                    BrickGuard.getPrefs().edit().putString("brickguard_email", email).apply();
                    // Save report state in shared preferences
                    BrickGuard.getPrefs().edit().putBoolean("brickguard_send_report", sendReport).apply();
                    Toast.makeText(getContext(), "Email settings saved", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", ((dialog, which) -> {
                    dialog.dismiss();
                }))
                .show();

        // Handle actions
        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                return updateEmailState(editText, emailSetText);
            }
            return false;
        });

        emailSetText.setText(email.equals("nil") ? "No email set" : "Email set to: " + email);

        SwitchMaterial emailReportSwitch = emailDialog.findViewById(R.id.email_report_switch);
        emailReportSwitch.setOnClickListener(v -> {});
        emailReportSwitch.setOnCheckedChangeListener((v, isChecked) -> {
            sendReport = isChecked;
        });
        emailReportSwitch.setChecked(sendReport);
    }

    private boolean updateEmailState(TextView editText, TextView emailSetText) {
        // Validate text
        CharSequence text = editText.getText();
        String textStr = text.toString();

        // Parse email with space behind
        if (textStr.endsWith(" ")) textStr = textStr.substring(0, textStr.length() - 1);

        // Invalid email address
        if (invalidEmail(textStr)) {
            editText.setError("Invalid email address");
            return false;
        }

        // Save email in state
        email = textStr;

        // Clear text
        editText.setText("");

        // Change emailSetText
        emailSetText.setText("Setting email to: " + textStr);
        return true;
    }

    private boolean invalidEmail(String textStr) {
        return (!textStr.contains("@") || !textStr.contains(".") || textStr.startsWith(".") ||
                textStr.endsWith(".") || textStr.contains(" ") || textStr.length() < 4);
    }
}
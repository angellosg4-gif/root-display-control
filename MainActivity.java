package com.rootdisplaycontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String PREFS = "display_snapshot";
    private static final int MIN_DIMENSION = 320;
    private static final int MAX_DIMENSION = 10000;
    private static final int MIN_DENSITY = 72;
    private static final int MAX_DENSITY = 1000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText widthInput;
    private EditText heightInput;
    private EditText densityInput;
    private Switch autoAspectSwitch;
    private TextView rootStatus;
    private TextView statusMessage;
    private TextView currentResolution;
    private TextView currentDensity;
    private Button applyButton;
    private Button resetButton;
    private Button refreshButton;
    private boolean changingHeight;
    private boolean busy;
    private int aspectWidth = 9;
    private int aspectHeight = 16;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(R.layout.activity_main);

        widthInput = findViewById(R.id.widthInput);
        heightInput = findViewById(R.id.heightInput);
        densityInput = findViewById(R.id.densityInput);
        autoAspectSwitch = findViewById(R.id.autoAspectSwitch);
        rootStatus = findViewById(R.id.rootStatus);
        statusMessage = findViewById(R.id.statusMessage);
        currentResolution = findViewById(R.id.currentResolution);
        currentDensity = findViewById(R.id.currentDensity);
        applyButton = findViewById(R.id.applyButton);
        resetButton = findViewById(R.id.resetButton);
        refreshButton = findViewById(R.id.refreshButton);

        widthInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (autoAspectSwitch.isChecked() && !changingHeight && s.length() > 0) {
                    updateHeightFromAspect(s.toString());
                }
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        findViewById(R.id.refreshButton).setOnClickListener(v -> refreshDisplay());
        applyButton.setOnClickListener(v -> confirmApply());
        resetButton.setOnClickListener(v -> confirmRestore());
        refreshDisplay();
    }

    private void updateHeightFromAspect(String widthText) {
        try {
            int width = Integer.parseInt(widthText);
            int height = Math.round(width * (aspectHeight / (float) aspectWidth));
            changingHeight = true;
            heightInput.setText(String.valueOf(height));
            heightInput.setSelection(heightInput.length());
        } catch (NumberFormatException ignored) {
            // The final validation reports incomplete input when Apply is pressed.
        } finally {
            changingHeight = false;
        }
    }

    private void refreshDisplay() {
        if (busy) return;
        setBusy(true);
        statusMessage.setText("Comprobando acceso root y leyendo la pantalla…");
        worker.execute(() -> {
            try {
                ShellResult result = runRoot("wm size && wm density");
                if (result.exitCode != 0) {
                    throw new IllegalStateException(rootError(result));
                }
                DisplayState state = parseDisplayState(result.output);
                runOnUiThread(() -> {
                    rootStatus.setText("ROOT DISPONIBLE");
                    rootStatus.setTextColor(getColor(R.color.accent));
                    currentResolution.setText(state.activeWidth + " × " + state.activeHeight);
                    currentDensity.setText(state.activeDensity + " DPI");
                    statusMessage.setText("Valores leídos. Ya puedes aplicar un perfil.");
                    if (widthInput.getText().length() == 0) {
                        widthInput.setText(String.valueOf(state.activeWidth));
                        heightInput.setText(String.valueOf(state.activeHeight));
                        densityInput.setText(String.valueOf(state.activeDensity));
                    }
                    aspectWidth = Math.max(1, state.activeWidth);
                    aspectHeight = Math.max(1, state.activeHeight);
                    setBusy(false);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    rootStatus.setText("ROOT NO DISPONIBLE");
                    rootStatus.setTextColor(getColor(R.color.danger));
                    statusMessage.setText(error.getMessage());
                    setBusy(false);
                });
            }
        });
    }

    private void confirmApply() {
        final int width;
        final int height;
        final int density;
        try {
            width = parseBounded(widthInput, "Width", MIN_DIMENSION, MAX_DIMENSION);
            height = parseBounded(heightInput, "Height", MIN_DIMENSION, MAX_DIMENSION);
            density = parseBounded(densityInput, "DPI", MIN_DENSITY, MAX_DENSITY);
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("¿Aplicar los cambios?")
                .setMessage("Esto cambia la resolución y la densidad de todo el dispositivo. Se guardarán los valores originales para que puedas restaurarlos.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Aplicar", (dialog, which) -> applyProfile(width, height, density))
                .show();
    }

    private void applyProfile(int width, int height, int density) {
        if (busy) return;
        setBusy(true);
        statusMessage.setText("Solicitando root y aplicando el perfil…");
        worker.execute(() -> {
            try {
                ShellResult beforeResult = runRoot("wm size && wm density");
                if (beforeResult.exitCode != 0) throw new IllegalStateException(rootError(beforeResult));
                DisplayState before = parseDisplayState(beforeResult.output);
                saveOriginalIfMissing(before);

                ShellResult sizeResult = runRoot("wm size " + width + "x" + height);
                if (sizeResult.exitCode != 0) throw new IllegalStateException(rootError(sizeResult));
                ShellResult densityResult = runRoot("wm density " + density);
                if (densityResult.exitCode != 0) {
                    restoreState(before);
                    throw new IllegalStateException(rootError(densityResult));
                }
                runOnUiThread(() -> {
                    currentResolution.setText(width + " × " + height);
                    currentDensity.setText(density + " DPI");
                    statusMessage.setText("Perfil aplicado. Los valores originales están guardados.");
                    Toast.makeText(this, "Perfil aplicado", Toast.LENGTH_SHORT).show();
                    setBusy(false);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    statusMessage.setText(error.getMessage());
                    Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                    setBusy(false);
                });
            }
        });
    }

    private void confirmRestore() {
        new AlertDialog.Builder(this)
                .setTitle("¿Restaurar la pantalla anterior?")
                .setMessage("Vuelve a los valores guardados antes del primer cambio.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Restaurar", (dialog, which) -> restoreOriginal())
                .show();
    }

    private void restoreOriginal() {
        if (busy) return;
        setBusy(true);
        statusMessage.setText("Restaurando los valores guardados…");
        worker.execute(() -> {
            try {
                ShellResult result;
                if (getPreferences(0).getBoolean("saved", false)) {
                    result = restoreSavedSnapshot();
                } else {
                    result = runRoot("wm size reset && wm density reset");
                }
                if (result.exitCode != 0) throw new IllegalStateException(rootError(result));
                getPreferences(0).edit().clear().apply();
                runOnUiThread(() -> {
                    statusMessage.setText("Se restauraron los valores anteriores.");
                    Toast.makeText(this, "Pantalla restaurada", Toast.LENGTH_SHORT).show();
                    setBusy(false);
                    refreshDisplay();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    statusMessage.setText(error.getMessage());
                    Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                    setBusy(false);
                });
            }
        });
    }

    private void saveOriginalIfMissing(DisplayState state) {
        if (getPreferences(0).getBoolean("saved", false)) return;
        getPreferences(0).edit()
                .putBoolean("saved", true)
                .putBoolean("size_override", state.sizeOverride)
                .putInt("width", state.sizeOverride ? state.overrideWidth : state.physicalWidth)
                .putInt("height", state.sizeOverride ? state.overrideHeight : state.physicalHeight)
                .putBoolean("density_override", state.densityOverride)
                .putInt("density", state.densityOverride ? state.overrideDensity : state.physicalDensity)
                .apply();
    }

    private ShellResult restoreSavedSnapshot() throws Exception {
        android.content.SharedPreferences prefs = getPreferences(0);
        String sizeCommand = prefs.getBoolean("size_override", false)
                ? "wm size " + prefs.getInt("width", 0) + "x" + prefs.getInt("height", 0)
                : "wm size reset";
        String densityCommand = prefs.getBoolean("density_override", false)
                ? "wm density " + prefs.getInt("density", 0)
                : "wm density reset";
        ShellResult sizeResult = runRoot(sizeCommand);
        if (sizeResult.exitCode != 0) return sizeResult;
        return runRoot(densityCommand);
    }

    private void restoreState(DisplayState state) throws Exception {
        String sizeCommand = state.sizeOverride
                ? "wm size " + state.overrideWidth + "x" + state.overrideHeight
                : "wm size reset";
        String densityCommand = state.densityOverride
                ? "wm density " + state.overrideDensity
                : "wm density reset";
        runRoot(sizeCommand);
        runRoot(densityCommand);
    }

    private DisplayState parseDisplayState(String output) {
        Matcher physicalSize = Pattern.compile("Physical size:\\s*(\\d+)x(\\d+)").matcher(output);
        Matcher overrideSize = Pattern.compile("Override size:\\s*(\\d+)x(\\d+)").matcher(output);
        Matcher physicalDensity = Pattern.compile("Physical density:\\s*(\\d+)").matcher(output);
        Matcher overrideDensity = Pattern.compile("Override density:\\s*(\\d+)").matcher(output);
        if (!physicalSize.find() || !physicalDensity.find()) {
            throw new IllegalStateException("No se pudieron leer los valores wm. Comprueba que el dispositivo admite wm size y wm density.");
        }
        DisplayState state = new DisplayState();
        state.physicalWidth = Integer.parseInt(physicalSize.group(1));
        state.physicalHeight = Integer.parseInt(physicalSize.group(2));
        state.physicalDensity = Integer.parseInt(physicalDensity.group(1));
        state.sizeOverride = overrideSize.find();
        if (state.sizeOverride) {
            state.overrideWidth = Integer.parseInt(overrideSize.group(1));
            state.overrideHeight = Integer.parseInt(overrideSize.group(2));
        }
        state.densityOverride = overrideDensity.find();
        if (state.densityOverride) state.overrideDensity = Integer.parseInt(overrideDensity.group(1));
        state.activeWidth = state.sizeOverride ? state.overrideWidth : state.physicalWidth;
        state.activeHeight = state.sizeOverride ? state.overrideHeight : state.physicalHeight;
        state.activeDensity = state.densityOverride ? state.overrideDensity : state.physicalDensity;
        return state;
    }

    private int parseBounded(EditText field, String name, int minimum, int maximum) {
        String value = field.getText().toString().trim();
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(name + " debe estar entre " + minimum + " y " + maximum + ".");
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Introduce un valor válido para " + name.toLowerCase() + ".");
        }
    }

    private ShellResult runRoot(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("El comando root agotó el tiempo. Revisa la solicitud de superusuario e inténtalo de nuevo.");
        }
        try (InputStream input = process.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new ShellResult(process.exitValue(), new String(output.toByteArray(), StandardCharsets.UTF_8).trim());
        }
    }

    private String rootError(ShellResult result) {
        if (result.output == null || result.output.isEmpty()) {
            return "Falló el comando root (código " + result.exitCode + "). Autoriza el acceso en el gestor de superusuario e inténtalo de nuevo.";
        }
        return "Falló el comando root: " + result.output;
    }

    private void setBusy(boolean isBusy) {
        busy = isBusy;
        applyButton.setEnabled(!isBusy);
        resetButton.setEnabled(!isBusy);
        refreshButton.setEnabled(!isBusy);
        applyButton.setAlpha(isBusy ? 0.55f : 1f);
        resetButton.setAlpha(isBusy ? 0.55f : 1f);
        refreshButton.setAlpha(isBusy ? 0.55f : 1f);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private static final class ShellResult {
        final int exitCode;
        final String output;
        ShellResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static final class DisplayState {
        int physicalWidth;
        int physicalHeight;
        int physicalDensity;
        int overrideWidth;
        int overrideHeight;
        int overrideDensity;
        int activeWidth;
        int activeHeight;
        int activeDensity;
        boolean sizeOverride;
        boolean densityOverride;
    }
}

import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.chart.NumberAxis;
import javafx.scene.layout.GridPane;
import javafx.scene.chart.XYChart;
import javafx.scene.chart.LineChart;
import javafx.scene.text.*;
import javafx.collections.ObservableList;
import javafx.collections.FXCollections;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.HelpFormatter;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.util.MathArrays;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;
import jp.ac.kyoto_u.kuis.le4music.Player;
import jp.ac.kyoto_u.kuis.le4music.Player.Builder;
import jp.ac.kyoto_u.kuis.le4music.LineChartWithSpectrogram;
import static jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils.verbose;
import jp.ac.kyoto_u.kuis.le4music.Recorder;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.LineUnavailableException;
import org.apache.commons.cli.ParseException;

public final class karaoke extends Application {

        private static final Options options = new Options();
        private static final String helpMessage = MethodHandles.lookup().lookupClass().getName()
                        + " [OPTIONS] <WAVFILE>";

        static {
                /* コマンドラインオプション定義 */
                options.addOption("h", "help", false, "display this help and exit");
                options.addOption("v", "verbose", false, "Verbose output");
                options.addOption("m", "mixer", true,
                                "Index of the Mixer object that supplies a SourceDataLine object. "
                                                + "To check the proper index, use CheckAudioSystem");
                options.addOption("l", "loop", false, "Loop playback");
                options.addOption("o", "outfile", true, "Output file");
                options.addOption("r", "rate", true, "Sampling rate [Hz]");
                options.addOption("f", "frame", true,
                                "Frame duration [seconds] " + "(Default: " + Le4MusicUtils.frameDuration + ")");
                options.addOption("i", "interval", true, "Frame notification interval [seconds] " + "(Default: "
                                + Le4MusicUtils.frameInterval + ")");
                options.addOption("b", "buffer", true, "Duration of line buffer [seconds]");
                options.addOption("d", "duration", true, "Duration of spectrogram [seconds]");
                options.addOption(null, "amp-lo", true, "Lower bound of amplitude [dB] (Default: "
                                + Le4MusicUtils.spectrumAmplitudeLowerBound + ")");
                options.addOption(null, "amp-up", true, "Upper bound of amplitude [dB] (Default: "
                                + Le4MusicUtils.spectrumAmplitudeUpperBound + ")");
                options.addOption(null, "freq-lo", true, "Lower bound of frequency [Hz] (Default: 0.0)");
                options.addOption(null, "freq-up", true, "Upper bound of frequency [Hz] (Default: Nyquist)");
        }

        @Override /* Application */
        public final void start(final Stage primaryStage)
                        throws IOException, UnsupportedAudioFileException, LineUnavailableException, ParseException {
                /* コマンドライン引数処理 */
                final String[] args = getParameters().getRaw().toArray(new String[0]);
                final CommandLine cmd = new DefaultParser().parse(options, args);
                if (cmd.hasOption("help")) {
                        new HelpFormatter().printHelp(helpMessage, options);
                        Platform.exit();
                        return;
                }
                verbose = cmd.hasOption("verbose");

                final double frameDuration = Optional.ofNullable(cmd.getOptionValue("frame")).map(Double::parseDouble)
                                .orElse(Le4MusicUtils.frameDuration);

                final String[] pargs = cmd.getArgs();
                if (pargs.length < 1) {
                        System.out.println("WAVFILE is not given.");
                        new HelpFormatter().printHelp(helpMessage, options);
                        Platform.exit();
                        return;
                }
                final File wavFile = new File(pargs[0]);

                final double duration = Optional.ofNullable(cmd.getOptionValue("duration")).map(Double::parseDouble)
                                .orElse(Le4MusicUtils.spectrogramDuration);
                final double interval = Optional.ofNullable(cmd.getOptionValue("interval")).map(Double::parseDouble)
                                .orElse(Le4MusicUtils.frameInterval);
                /* シフトのサンプル数 */
                final double shiftDuration = Optional.ofNullable(cmd.getOptionValue("shift")).map(Double::parseDouble)
                                .orElse(Le4MusicUtils.frameDuration / 8);
                final int shiftSize = (int) Math.round(shiftDuration * 44100);

                File file = new File("/Users/mizuki/le4-music/le4-music/src/kiseki.txt");
                FileReader fr = new FileReader(file);
                BufferedReader br = new BufferedReader(fr);
                ArrayList<String> kashi = new ArrayList<>();
                String text;
                while ((text = br.readLine()) != null) {
                        kashi.add(text);
                }
                br.close();

                /* Player を作成 */
                final Player.Builder builder = Player.builder(wavFile);
                Optional.ofNullable(cmd.getOptionValue("mixer")).map(Integer::parseInt)
                                .map(index -> AudioSystem.getMixerInfo()[index]).ifPresent(builder::mixer);
                if (cmd.hasOption("loop"))
                        builder.loop();
                Optional.ofNullable(cmd.getOptionValue("buffer")).map(Double::parseDouble)
                                .ifPresent(builder::bufferDuration);
                Optional.ofNullable(cmd.getOptionValue("frame")).map(Double::parseDouble)
                                .ifPresent(builder::frameDuration);
                builder.interval(interval);
                builder.daemon();
                final Player player = builder.build();

                /* Recorderオブジェクトを生成 */
                final Recorder.Builder builder_r = Recorder.builder();
                Optional.ofNullable(cmd.getOptionValue("rate")).map(Float::parseFloat).ifPresent(builder_r::sampleRate);
                Optional.ofNullable(cmd.getOptionValue("mixer")).map(Integer::parseInt)
                                .map(index -> AudioSystem.getMixerInfo()[index]).ifPresent(builder_r::mixer);
                Optional.ofNullable(cmd.getOptionValue("outfile")).map(File::new).ifPresent(builder_r::wavFile);
                builder_r.frameDuration(frameDuration);
                Optional.ofNullable(cmd.getOptionValue("interval")).map(Double::parseDouble)
                                .ifPresent(builder_r::interval);
                builder_r.daemon();
                final Recorder recorder = builder_r.build();

                /* データ処理スレッド player */
                final ExecutorService executor = Executors.newSingleThreadExecutor();
                /* データ処理スレッド recoder */
                final ExecutorService executor_r = Executors.newSingleThreadExecutor();

                /* 窓関数とFFTのサンプル数 */
                final int fftSize = 1 << Le4MusicUtils.nextPow2(player.getFrameSize());
                final int fftSize2 = (fftSize >> 1) + 1;

                /* 窓関数を求め，それを正規化する */
                final double[] window = MathArrays.normalizeArray(Le4MusicUtils.hanning(player.getFrameSize()), 1.0);

                /* 各フーリエ変換係数に対応する周波数 */
                final double[] freqs = IntStream.range(0, fftSize2)
                                .mapToDouble(i -> i * player.getSampleRate() / fftSize).toArray();

                /* フレーム数 */
                final int frames = (int) Math.round(duration / interval);

                /* データ系列を作成 */
                ObservableList<XYChart.Data<Number, Number>> data_waveform = IntStream
                                .range(-recorder.getFrameSize(), 0).mapToDouble(i -> i / recorder.getSampleRate())
                                .mapToObj(t -> new XYChart.Data<Number, Number>(t, 0.0))
                                .collect(Collectors.toCollection(FXCollections::observableArrayList));

                /* データ系列に名前をつける */
                final XYChart.Series<Number, Number> series_waveform = new XYChart.Series<>("karaoke", data_waveform);

                /* 軸を作成 for player */
                final NumberAxis xAxis = new NumberAxis(/* axisLabel = */ "Time (seconds)",
                                /* lowerBound = */ -duration, /* upperBound = */ 0,
                                /* tickUnit = */ Le4MusicUtils.autoTickUnit(duration));
                xAxis.setAnimated(false);

                final double freqLowerBound = Optional.ofNullable(cmd.getOptionValue("freq-lo"))
                                .map(Double::parseDouble).orElse(0.0);
                if (freqLowerBound < 0.0)
                        throw new IllegalArgumentException("freq-lo must be non-negative: " + freqLowerBound);
                final double freqUpperBound = Optional.ofNullable(cmd.getOptionValue("freq-up"))
                                .map(Double::parseDouble).orElse(player.getNyquist());
                if (freqUpperBound <= freqLowerBound)
                        throw new IllegalArgumentException("freq-up must be larger than freq-lo: " + "freq-lo = "
                                        + freqLowerBound + ", freq-up = " + freqUpperBound);
                final NumberAxis yAxis = new NumberAxis(/* axisLabel = */ "Frequency (Hz)", /* lowerBound = */ 0.0,
                                /* upperBound = */ 600, /* tickUnit = */ Le4MusicUtils.autoTickUnit(600));
                yAxis.setAnimated(false);

                /* 軸を作成 for recoder */
                final NumberAxis xAxis_waveform = new NumberAxis("Time (seconds)", -duration, 0.0,
                                Le4MusicUtils.autoTickUnit(duration));

                final NumberAxis yAxis_waveform = new NumberAxis(/* axisLabel = */ "Freqency (Hz)",
                                /* lowerBound = */ 0.0, /* upperBound = */ 1000,
                                /* tickUnit = */ Le4MusicUtils.autoTickUnit(1000));

                /* スペクトログラム表示chart */
                final LineChartWithSpectrogram<Number, Number> chart = new LineChartWithSpectrogram<>(xAxis, yAxis);
                chart.setParameters(frames, fftSize2, player.getNyquist());
                chart.setTitle("Spectrogram");
                chart.setAnimated(false);
                chart.setLegendVisible(false);

                final LineChart<Number, Number> chart_waveform = new LineChart<Number, Number>(xAxis_waveform,
                                yAxis_waveform);
                chart_waveform.setTitle("Waveform");
                chart_waveform.setLegendVisible(false);
                /* データの追加・削除時にアニメーション（フェードイン・アウトなど）しない */
                chart_waveform.setAnimated(false);
                /* データアイテムに対してシンボルを作成しない */
                chart_waveform.setCreateSymbols(false);
                chart_waveform.getData().add(series_waveform);

                Text t = new Text("");
                t.setFont(new Font(20));
                t.setText("test");

                GridPane gridPane = new GridPane();
                gridPane.setMinSize(400, 600);
                gridPane.add(chart_waveform, 0, 0); // recorder
                gridPane.add(chart, 1, 0); // player
                gridPane.add(t, 0, 1); // kashi

                /* グラフ描画 */
                final Scene scene = new Scene(gridPane);
                scene.getStylesheets().add("le4music.css");
                primaryStage.setScene(scene);
                primaryStage.setTitle(getClass().getName());
                /* ウインドウを閉じたときに他スレッドも停止させる */
                primaryStage.setOnCloseRequest(req -> executor.shutdown());
                primaryStage.setOnCloseRequest(req -> executor_r.shutdown());
                primaryStage.show();
                Platform.setImplicitExit(true);

                String[] pitch = new String[] { "ド", "ド#", "レ", "ミ♭", "ミ", "ファ", "ファ#", "ソ", "ソ#", "ラ", "シ♭", "シ" };

                recorder.addAudioFrameListener((frame, position) -> Platform.runLater(() -> {
                        final double rms = Arrays.stream(frame).map(x -> x * x).average().orElse(0.0);
                        final double logRms = 20.0 * Math.log10(rms);
                        final double posInSec = position / recorder.getSampleRate();
                        // System.out.printf("Position %d (%.2f sec), RMS %f dB%n", position, posInSec,
                        // logRms);

                        final int fftSize_f0 = 1 << Le4MusicUtils.nextPow2(recorder.getFrameSize());
                        final double[] window_f0 = MathArrays.normalizeArray(
                                        Arrays.copyOf(Le4MusicUtils.hanning(recorder.getFrameSize()), fftSize_f0), 1.0);
                        final Stream<Complex[]> spectrogram_f0 = Le4MusicUtils.sliding(frame, window_f0, shiftSize)
                                        .map(f -> Le4MusicUtils.rfft(f));
                        double[][] specLog_f0 = spectrogram_f0
                                        .map(sp -> Arrays.stream(sp).mapToDouble(c -> c.abs()).toArray())
                                        .toArray(n -> new double[n][]);
                        double[] freq0 = new double[specLog_f0.length];
                        for (int m = 0; m < specLog_f0.length; m++) {
                                for (int k = 0; k < specLog_f0[m].length; k++) {
                                        if (k * recorder.getSampleRate() / fftSize_f0 > 500.0) {
                                                specLog_f0[m][k] = 0.0;
                                        }
                                }
                                freq0[m] = Le4MusicUtils.argmax(specLog_f0[m]);
                        }
                        double min = freq0[0];
                        for (int z = 0; z < freq0.length; z++) {
                                if (min > freq0[z]) {
                                        min = freq0[z];
                                }
                        }
                        // 軸の更新は、音楽が止まればやめるようにする
                        if (posInSec < 273) {
                                xAxis_waveform.setLowerBound(posInSec - duration);
                                xAxis_waveform.setUpperBound(posInSec);
                        }

                        if (posInSec > duration) {
                                data_waveform.remove(0, 1);
                        }
                        if (logRms < -100) {
                                min = 0;
                        }
                        if ((position / 320) % 8 == 0) {
                                XYChart.Data<Number, Number> datum = new XYChart.Data<Number, Number>(posInSec,
                                                min * recorder.getSampleRate() / fftSize_f0);
                                data_waveform.add(datum);
                                datum = new XYChart.Data<Number, Number>(posInSec + 0.02,
                                                min * recorder.getSampleRate() / fftSize_f0);
                                data_waveform.add(datum);
                                datum = new XYChart.Data<Number, Number>(posInSec + 0.04,
                                                min * recorder.getSampleRate() / fftSize_f0);
                                data_waveform.add(datum);
                                datum = new XYChart.Data<Number, Number>(posInSec + 0.06,
                                                min * recorder.getSampleRate() / fftSize_f0);
                                data_waveform.add(datum);
                                datum = new XYChart.Data<Number, Number>(posInSec + 0.08,
                                                min * recorder.getSampleRate() / fftSize_f0);
                                data_waveform.add(datum);
                                datum = new XYChart.Data<Number, Number>(posInSec + 0.10,
                                                min * recorder.getSampleRate() / fftSize_f0);
                                data_waveform.add(datum);
                                datum = new XYChart.Data<Number, Number>(posInSec + 0.12,
                                                min * recorder.getSampleRate() / fftSize_f0);
                                data_waveform.add(datum);
                                datum = new XYChart.Data<Number, Number>(posInSec + 0.14,
                                                min * recorder.getSampleRate() / fftSize_f0);
                                data_waveform.add(datum);
                                System.out.printf("Position %d (%.2f sec), RMS %f dB%n", position, posInSec, logRms);
                                int noteNum = (int) Math.round(
                                                Le4MusicUtils.hz2nn(min * recorder.getSampleRate() / fftSize_f0));
                                // System.out.println(pitch[noteNum % 12]);
                        }
                }));
                recorder.start();

                player.addAudioFrameListener((frame, position) -> executor.execute(() ->

                {
                        final double[] wframe = MathArrays.ebeMultiply(frame, window);
                        final Complex[] spectrum = Le4MusicUtils.rfft(Arrays.copyOf(wframe, fftSize));
                        final double posInSec = position / player.getSampleRate();

                        /* スペクトログラム描画 */
                        chart.addSpectrum(spectrum);

                        final int fftSize_f0 = 1 << Le4MusicUtils.nextPow2(recorder.getFrameSize());
                        final double[] window_f0 = MathArrays.normalizeArray(
                                        Arrays.copyOf(Le4MusicUtils.hanning(recorder.getFrameSize()), fftSize_f0), 1.0);
                        final Stream<Complex[]> spectrogram_f0 = Le4MusicUtils.sliding(frame, window_f0, shiftSize)
                                        .map(f -> Le4MusicUtils.rfft(f));
                        double[][] specLog_f0 = spectrogram_f0
                                        .map(sp -> Arrays.stream(sp).mapToDouble(c -> c.abs()).toArray())
                                        .toArray(n -> new double[n][]);
                        double[] freq0 = new double[specLog_f0.length];
                        for (int m = 0; m < specLog_f0.length; m++) {
                                for (int k = 0; k < specLog_f0[m].length; k++) {
                                        if (k * recorder.getSampleRate() / fftSize_f0 > 500.0) {
                                                specLog_f0[m][k] = 0.0;
                                        }
                                }
                                freq0[m] = Le4MusicUtils.argmax(specLog_f0[m]);
                        }
                        double min = freq0[0];
                        for (int z = 0; z < freq0.length; z++) {
                                if (min > freq0[z]) {
                                        min = freq0[z];
                                }
                        }
                        if (posInSec > 20 && posInSec < 63 && (posInSec - 22) % 10 == 0.0) {
                                System.out.println("here01");
                                t.setText(kashi.get(((int) posInSec - 22) / 10) + "\n"
                                                + kashi.get(((int) posInSec - 22) / 10 + 1));// 0-3
                                System.out.println("second ly is" + (((int) posInSec - 22) / 10 + 1));
                        } else if (posInSec >= 63 && posInSec < 107 && (posInSec - 25) % 10 == 0.0) {
                                t.setText(kashi.get(((int) posInSec - 25) / 10) + "\n"
                                                + kashi.get(((int) posInSec - 25) / 10 + 1)); // 4-8
                        } else if (posInSec < 140 && (posInSec - 27) % 10 == 0.0 && posInSec >= 107) {
                                System.out.println("here02");
                                System.out.println("second ly is" + ((((int) posInSec - 27) / 10) + 1));
                                t.setText(kashi.get((int) (posInSec - 27) / 10) + "\n"
                                                + kashi.get((((int) posInSec - 27) / 10) + 1));// 9-11
                                System.out.println(kashi.get((int) (posInSec - 26) / 10));
                        } else if ((posInSec - 29) % 10 == 0.0 && posInSec < 169 && posInSec >= 140) {
                                System.out.println("here03");
                                t.setText(kashi.get(((int) posInSec - 29) / 10) + "\n"
                                                + kashi.get(((int) posInSec - 29) / 10 + 1));// 12,13
                        } else if (posInSec < 180 && posInSec >= 170) {
                                System.out.println("here04");
                                t.setText(kashi.get(16)); // 16
                        } else if (posInSec >= 180 && posInSec < 190) {
                                t.setText(kashi.get(17)); // 17
                        } else if (posInSec < 234 && (posInSec - 11) % 10 == 0.0 && posInSec >= 190) {
                                System.out.println("here05");
                                t.setText(kashi.get(((int) posInSec - 11) / 10) + "\n"
                                                + kashi.get(((int) posInSec - 11) / 10 + 1));// 18-21
                        } else if (posInSec < 247 && posInSec >= 234) {
                                System.out.println("here06");
                                t.setText(kashi.get(22));
                        } else if (posInSec >= 248 && posInSec <= 258) {
                                if ((posInSec - 18) % 10 == 0.0) {
                                        System.out.println("here07");
                                        t.setText(kashi.get((int) (posInSec - 18) / 10) + "\n"
                                                        + kashi.get(((int) posInSec - 18) / 10 + 1));
                                }
                        }
                        /* 軸を更新 */
                        xAxis.setUpperBound(posInSec);
                        xAxis.setLowerBound(posInSec - duration);
                }));

                /* 録音開始 */
                Platform.runLater(player::start);

        }
}

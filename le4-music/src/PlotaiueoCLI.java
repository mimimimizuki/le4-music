import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.imageio.ImageIO;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.LineUnavailableException;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.layout.GridPane;
import javafx.scene.Scene;
import javafx.scene.chart.XYChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.image.WritableImage;
import javafx.collections.ObservableList;
import javafx.collections.FXCollections;
import javafx.embed.swing.SwingFXUtils;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.HelpFormatter;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.util.MathArrays;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;
import jp.ac.kyoto_u.kuis.le4music.CheckAudioSystem;
import jp.ac.kyoto_u.kuis.le4music.LineChartWithSpectrogram;
import jp.ac.kyoto_u.kuis.le4music.AudioFrameProvider;
import jp.ac.kyoto_u.kuis.le4music.Player;
import jp.ac.kyoto_u.kuis.le4music.Player.Builder;
import static jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils.verbose;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.apache.commons.cli.ParseException;

public final class PlotaiueoCLI extends Application {

        private static final Options options = new Options();
        private static final String helpMessage = MethodHandles.lookup().lookupClass().getName()
                        + " [OPTIONS] <WAVFILE>";

        static {
                /* コマンドラインオプション定義 */
                options.addOption("h", "help", false, "Display this help and exit");
                options.addOption("o", "outfile", true,
                                "Output image file (Default: " + MethodHandles.lookup().lookupClass().getSimpleName()
                                                + "." + Le4MusicUtils.outputImageExt + ")");
                options.addOption("m", "mixer", true, "Index of Mixer object that supplies a SourceDataLine object. "
                                + "To check the proper index, use CheckAudioSystem");
                options.addOption("i", "interval", true, "Frame notification interval [seconds] " + "(Default: "
                                + Le4MusicUtils.frameInterval + ")");
                options.addOption(null, "amp-lo", true, "Lower bound of amplitude [dB] (Default: "
                                + Le4MusicUtils.spectrumAmplitudeLowerBound + ")");
                options.addOption(null, "amp-up", true, "Upper bound of amplitude [dB] (Default: "
                                + Le4MusicUtils.spectrumAmplitudeUpperBound + ")");
                options.addOption(null, "freq-lo", true, "Lower bound of frequency [Hz] (Default: 0.0)");
                options.addOption(null, "freq-up", true, "Upper bound of frequency [Hz] (Default: Nyquist)");
        }

        @Override
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
                final String[] pargs = cmd.getArgs();
                if (pargs.length < 1) {
                        System.out.println("WAVFILE is not given.");
                        new HelpFormatter().printHelp(helpMessage, options);
                        Platform.exit();
                        return;
                }
                final File[] wavFile = new File[5];
                wavFile[0] = new File(pargs[0]);
                wavFile[1] = new File(pargs[1]);
                wavFile[2] = new File(pargs[2]);
                wavFile[3] = new File(pargs[3]);
                wavFile[4] = new File(pargs[4]);

                double[][] average = new double[5][13];// 最尤推定した結果の平均
                double[][] s_power = new double[5][13];// 最尤推定した結果の分散

                for (int v = 0; v < 5; v++) {
                        final AudioInputStream stream = AudioSystem.getAudioInputStream(wavFile[v]);
                        final double[] waveform = Le4MusicUtils.readWaveformMonaural(stream);
                        final AudioFormat format = stream.getFormat();
                        final double sampleRate = format.getSampleRate();
                        final double nyquist = sampleRate * 0.5;
                        stream.close();

                        /*
                         * fftSize = 2ˆp >= waveform.length を満たすfftSize を求める 2ˆp はシフト演算で求める
                         */
                        /* 窓関数とFFTのサンプル数 */
                        final double frameDuration = Optional.ofNullable(cmd.getOptionValue("frame"))
                                        .map(Double::parseDouble).orElse(Le4MusicUtils.frameDuration);
                        final int frameSize = (int) Math.round(frameDuration * sampleRate);
                        final int fftSize = 1 << Le4MusicUtils.nextPow2(waveform.length);
                        final int fftSize2 = (fftSize >> 1) + 1;
                        /*
                         * 信号の長さをfftSize に伸ばし， 長さが足りない部分は0 で埋める． 振幅を信号長で正規化する．
                         */
                        /* シフトのサンプル数 */
                        final double shiftDuration = Optional.ofNullable(cmd.getOptionValue("shift"))
                                        .map(Double::parseDouble).orElse(Le4MusicUtils.frameDuration / 8);
                        final int shiftSize = (int) Math.round(shiftDuration * sampleRate);
                        /* 窓関数を求め， それを正規化する */
                        final double[] window = MathArrays
                                        .normalizeArray(Arrays.copyOf(Le4MusicUtils.hanning(frameSize), fftSize), 1.0);
                        /* 短時間フーリエ変換本体 */
                        final Stream<Complex[]> spectrogram = Le4MusicUtils.sliding(waveform, window, shiftSize)
                                        .map(frame -> Le4MusicUtils.rfft(frame));

                        /* 複素スペクトログラムを対数振幅スペクトログラムに */
                        double[][] specLog = spectrogram
                                        .map(sp -> Arrays.stream(sp).mapToDouble(c -> c.abs()).toArray())
                                        .toArray(n -> new double[n][]);

                        /* 参考： フレーム数と各フレーム先頭位置の時刻 */
                        final double[] times = IntStream.range(0, specLog.length).mapToDouble(i -> i * shiftDuration)
                                        .toArray();

                        /* 参考： 各フーリエ変換係数に対応する周波数 */
                        final double[] freqs = IntStream.range(0, fftSize2).mapToDouble(i -> i * sampleRate / fftSize)
                                        .toArray();

                        Complex[][] cepstrum = new Complex[times.length][];
                        final double[][] ceps = new double[times.length][];
                        for (int i = 0; i < times.length; i++) {
                                int fftSize_c = 1 << Le4MusicUtils.nextPow2(specLog[i].length);
                                int fftSize2_c = (fftSize_c >> 1) + 1;
                                double[] src = Arrays.stream(Arrays.copyOf(specLog[i], fftSize_c)).toArray();
                                cepstrum[i] = Le4MusicUtils.fft(src);
                                ceps[i] = new double[13];
                                for (int j = 0; j < 13; j++) {
                                        ceps[i][j] = cepstrum[i][j].getReal();
                                } // これを１３こだけ使う予定
                        }
                        for (int i = 0; i < 13; i++) {
                                double sum = 0;
                                for (int j = 0; j < times.length; j++) {
                                        sum += ceps[j][i];
                                }
                                average[v][i] = sum / times.length;
                        }
                        for (int i = 0; i < 13; i++) {
                                double sum = 0;
                                for (int j = 0; j < times.length; j++) {
                                        sum += Math.pow((average[v][i] - ceps[j][i]), 2);
                                }
                                s_power[v][i] = sum / times.length;
                        }
                }

                final File wavFile_tes = new File(pargs[5]);
                System.out.println("test file is " + wavFile_tes);
                final AudioInputStream stream = AudioSystem.getAudioInputStream(wavFile_tes);
                final double[] waveform = Le4MusicUtils.readWaveformMonaural(stream);
                final AudioFormat format = stream.getFormat();
                final double sampleRate = format.getSampleRate();
                final double nyquist = sampleRate * 0.5;
                stream.close();

                final double interval = Optional.ofNullable(cmd.getOptionValue("interval")).map(Double::parseDouble)
                                .orElse(Le4MusicUtils.frameInterval);

                final ExecutorService executor = Executors.newSingleThreadExecutor();

                /*
                 * fftSize = 2ˆp >= waveform.length を満たすfftSize を求める 2ˆp はシフト演算で求める
                 */
                /* 窓関数とFFTのサンプル数 */
                final double frameDuration = Optional.ofNullable(cmd.getOptionValue("frame")).map(Double::parseDouble)
                                .orElse(Le4MusicUtils.frameDuration);
                final int frameSize = (int) Math.round(frameDuration * sampleRate);
                final int fftSize = 1 << Le4MusicUtils.nextPow2(waveform.length);
                final int fftSize2 = (fftSize >> 1) + 1;
                /*
                 * 信号の長さをfftSize に伸ばし， 長さが足りない部分は0 で埋める． 振幅を信号長で正規化する．
                 */
                /* シフトのサンプル数 */
                final double shiftDuration = Optional.ofNullable(cmd.getOptionValue("shift")).map(Double::parseDouble)
                                .orElse(Le4MusicUtils.frameDuration / 8);
                final int shiftSize = (int) Math.round(shiftDuration * sampleRate);
                /* 窓関数を求め， それを正規化する */
                final double[] window = MathArrays
                                .normalizeArray(Arrays.copyOf(Le4MusicUtils.hanning(frameSize), fftSize), 1.0);
                /* 短時間フーリエ変換本体 (for aiueo) */
                final Stream<Complex[]> spectrogram = Le4MusicUtils.sliding(waveform, window, shiftSize)
                                .map(frame -> Le4MusicUtils.rfft(frame));

                /* 複素スペクトログラムを振幅スペクトログラムに (for aiueo) */
                double[][] specLog = spectrogram.map(sp -> Arrays.stream(sp).mapToDouble(c -> c.abs()).toArray())
                                .toArray(n -> new double[n][]);

                /* 参考： フレーム数と各フレーム先頭位置の時刻 */
                final double[] times = IntStream.range(0, specLog.length).mapToDouble(i -> i * shiftDuration).toArray();

                Complex[][] cepstrum = new Complex[times.length][];
                final double[][] ceps = new double[times.length][13];
                int[] index = new int[times.length];

                for (int i = 0; i < times.length; i++) {
                        int fftSize_c = 1 << Le4MusicUtils.nextPow2(specLog[i].length);
                        int fftSize2_c = (fftSize_c >> 1) + 1;
                        double[] src = Arrays.stream(Arrays.copyOf(specLog[i], fftSize_c)).toArray();
                        cepstrum[i] = Le4MusicUtils.fft(src);
                        for (int j = 0; j < 13; j++) {
                                ceps[i][j] = cepstrum[i][j].getReal();
                        } // これを１３こだけ使う予定

                }
                for (int i = 0; i < times.length; i++) {

                        double[] L = new double[5];
                        for (int aiueo = 0; aiueo < 5; aiueo++) {
                                double sum = 0;
                                for (int d = 0; d < 13; d++) {
                                        sum += Math.log(Math.sqrt(s_power[aiueo][d]))
                                                        + (Math.pow((ceps[i][d] - average[aiueo][d]), 2)
                                                                        / (2 * s_power[aiueo][d]));
                                }
                                L[aiueo] = -1 * sum;
                        }
                        index[i] = Le4MusicUtils.argmax(L);
                }

                /* データ系列を作成 (for aiueo) */
                final ObservableList<XYChart.Data<Number, Number>> data = IntStream.range(0, index.length)
                                .mapToObj(i -> new XYChart.Data<Number, Number>(i * shiftDuration, index[i]))
                                .collect(Collectors.toCollection(FXCollections::observableArrayList));

                /* データ系列に名前をつける (for aiueo) with animation */
                final XYChart.Series<Number, String> series = new XYChart.Series<>();

                /* X 軸を作成 (for aiueo) */
                final double duration = (waveform.length - 1) / sampleRate;
                final NumberAxis xAxis = new NumberAxis(/* axisLabel = */ "Time (seconds)", /* lowerBound = */ 0.0,
                                /* upperBound = */ duration, /* tickUnit = */ Le4MusicUtils.autoTickUnit(duration));
                xAxis.setAnimated(false);

                /* Y 軸を作成 (for aiueo) */
                final CategoryAxis yAxis = new CategoryAxis();
                yAxis.setAnimated(false);

                /* チャートを作成 (for aiueo) */
                final LineChart<Number, String> chart = new LineChart<>(xAxis, yAxis);
                chart.setTitle("aiueo");
                String[] CATEGORIES = new String[] { "a", "i", "u", "e", "o" };
                yAxis.setCategories(FXCollections.<String>observableArrayList(CATEGORIES));
                for (int i = 0; i < index.length; i++) {
                        if (index[i] == 0) {
                                series.getData().add(
                                                new XYChart.Data<Number, String>(i * shiftDuration, CATEGORIES[0]));
                        } else if (index[i] == 1) {
                                series.getData().add(
                                                new XYChart.Data<Number, String>(i * shiftDuration, CATEGORIES[1]));
                        } else if (index[i] == 2) {
                                series.getData().add(
                                                new XYChart.Data<Number, String>(i * shiftDuration, CATEGORIES[2]));
                        } else if (index[i] == 3) {
                                series.getData().add(
                                                new XYChart.Data<Number, String>(i * shiftDuration, CATEGORIES[3]));
                        } else {
                                series.getData().add(
                                                new XYChart.Data<Number, String>(i * shiftDuration, CATEGORIES[4]));
                        }
                }
                chart.setCreateSymbols(false);
                chart.setLegendVisible(false);
                chart.getData().add(series);

                /* グラフ描画 */
                final Scene scene = new Scene(chart, 800, 600);
                scene.getStylesheets().add("le4music.css");

                /* ウインドウ表示 */
                primaryStage.setScene(scene);
                primaryStage.setTitle(getClass().getName());
                primaryStage.show();

                /* チャートを画像ファイルへ出力 */
                Platform.runLater(() -> {
                        final String[] name_ext = Le4MusicUtils.getFilenameWithImageExt(
                                        Optional.ofNullable(cmd.getOptionValue("outfile")), getClass().getSimpleName());
                        final WritableImage image = scene.snapshot(null);
                        try {
                                ImageIO.write(SwingFXUtils.fromFXImage(image, null), name_ext[1],
                                                new File(name_ext[0] + "." + name_ext[1]));
                        } catch (IOException e) {
                                e.printStackTrace();
                        }
                });

        }
}

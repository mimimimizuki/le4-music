import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.imageio.ImageIO;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.collections.FXCollections;
import javafx.scene.chart.XYChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.image.WritableImage;
import javafx.embed.swing.SwingFXUtils;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.HelpFormatter;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.util.MathArrays;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;
import jp.ac.kyoto_u.kuis.le4music.LineChartWithSpectrogram;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.apache.commons.cli.ParseException;

public final class PlotcodeCLI extends Application {

        private static final Options options = new Options();
        private static final String helpMessage = MethodHandles.lookup().lookupClass().getName()
                        + " [OPTIONS] <WAVFILE>";

        static {
                /* コマンドラインオプション定義 */
                options.addOption("h", "help", false, "Display this help and exit");
                options.addOption("o", "outfile", true,
                                "Output image file (Default: " + MethodHandles.lookup().lookupClass().getSimpleName()
                                                + "." + Le4MusicUtils.outputImageExt + ")");
                options.addOption("f", "frame", true,
                                "Duration of frame [seconds] (Default: " + Le4MusicUtils.frameDuration + ")");
                options.addOption("s", "shift", true, "Duration of shift [seconds] (Default: frame/8)");
        }

        @Override
        public final void start(final Stage primaryStage)
                        throws IOException, UnsupportedAudioFileException, ParseException {
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

                final File wavFile = new File(pargs[0]);

                /* WAVファイル読み込み */
                final AudioInputStream stream = AudioSystem.getAudioInputStream(wavFile);
                final double[] waveform = Le4MusicUtils.readWaveformMonaural(stream);
                final AudioFormat format = stream.getFormat();
                final double sampleRate = format.getSampleRate();
                final double nyquist = sampleRate * 0.5;
                stream.close();

                /* 窓関数とFFTのサンプル数 */
                final double frameDuration = Optional.ofNullable(cmd.getOptionValue("frame")).map(Double::parseDouble)
                                .orElse(Le4MusicUtils.frameDuration);
                final int frameSize = (int) Math.round(frameDuration * sampleRate);
                final int fftSize = 1 << Le4MusicUtils.nextPow2(frameSize);
                final int fftSize2 = (fftSize >> 1) + 1;

                /* シフトのサンプル数 */
                final double shiftDuration = Optional.ofNullable(cmd.getOptionValue("shift")).map(Double::parseDouble)
                                .orElse(Le4MusicUtils.frameDuration / 8);
                int shiftSize = (int) Math.round(shiftDuration * sampleRate);

                /* 窓関数を求め， それを正規化する */
                final double[] window = MathArrays
                                .normalizeArray(Arrays.copyOf(Le4MusicUtils.hanning(frameSize), fftSize), 1.0);
                /* 短時間フーリエ変換本体 */
                final Stream<Complex[]> spectrogram = Le4MusicUtils.sliding(waveform, window, shiftSize)
                                .map(frame -> Le4MusicUtils.rfft(frame));

                System.out.print(shiftSize);

                /* 複素スペクトログラムを振幅スペクトログラムに */
                final double[][] specLog = spectrogram.map(sp -> Arrays.stream(sp).mapToDouble(c -> c.abs()).toArray())
                                .toArray(n -> new double[n][]);

                /* 参考： フレーム数と各フレーム先頭位置の時刻 */
                final double[] times = IntStream.range(0, specLog.length).mapToDouble(i -> i * shiftDuration).toArray();

                /* 参考： 各フーリエ変換係数に対応する周波数 */
                final double[] freqs = IntStream.range(0, fftSize2).mapToDouble(i -> i * sampleRate / fftSize)
                                .toArray();
                // n mod 12 = 0
                int[] harmony_ans = new int[times.length];
                int[] code_counter = new int[12];
                for (int i = 0; i < times.length; i++) {
                        double[] chroma_v = new double[12]; // initialize
                        for (int j = 0; j < specLog[i].length; j++) {
                                double f = j * sampleRate / fftSize;
                                if (f != 0) {
                                        int n = (int) Math.round(Le4MusicUtils.hz2nn(f)); // この周波数に対応するノートナンバー
                                        if (n >= 0) {
                                                int code = n % 12; // そのノートナンバーを12で割ると0,..11がC, C#,...に対応する
                                                code_counter[code] += 1;
                                                chroma_v[code] += Math.abs(specLog[i][j]);
                                        }
                                }
                        }
                        double[] harmony = new double[24]; // initialize
                        for (int y = 0; y < 12; y++) {
                                chroma_v[y] = chroma_v[y] / code_counter[y]; // 現れるコードナンバーに偏りがないように正規化(平均をとる)
                        }
                        for (int w = 0; w <= 23; w++) {
                                if (w % 2 == 0) { // major
                                        harmony[w] = 1.0 * chroma_v[w / 2] + 0.5 * chroma_v[(w / 2 + 4) % 12]
                                                        + 0.8 * chroma_v[(w / 2 + 7) % 12];
                                } else { // minor
                                        harmony[w] = 1.0 * chroma_v[(w - 1) / 2]
                                                        + 0.5 * chroma_v[((w - 1) / 2 + 3) % 12]
                                                        + 0.8 * chroma_v[((w - 1) / 2 + 7) % 12];
                                }
                        }
                        harmony_ans[i] = Le4MusicUtils.argmax(harmony);
                }

                /* データ系列を作成 */
                final ObservableList<XYChart.Data<Number, Number>> data = IntStream.range(0, harmony_ans.length)
                                .mapToObj(i -> new XYChart.Data<Number, Number>(i * shiftDuration, harmony_ans[i]))
                                .collect(Collectors.toCollection(FXCollections::observableArrayList));

                /* データ系列に名前をつける */
                final XYChart.Series<Number, String> series = new XYChart.Series<>();

                /* X 軸を作成 */
                final double duration = (waveform.length - 1) / sampleRate;
                final NumberAxis xAxis = new NumberAxis(/* axisLabel = */ "Time (seconds)", /* lowerBound = */ 0.0,
                                /* upperBound = */ duration, /* tickUnit = */ Le4MusicUtils.autoTickUnit(duration));
                xAxis.setAnimated(false);

                /* Y 軸を作成 */
                final CategoryAxis yAxis = new CategoryAxis();
                yAxis.setAnimated(false);

                /* チャートを作成 */
                String[] chord = new String[] { "C Major", "C Minor", "C# Major", "C# Minor", "D Major", "D Minor",
                                "D# Major", "D# Minor", "E Major", "E Minor", "F Major", "F Minor", "F# Major",
                                "F# Minor", "G Major", "G Minor", "G# Major", "G# Minor", "A Major", "A Minor",
                                "A# Major", "A# Minor", "B Major", "B Minor" };
                yAxis.setCategories(FXCollections.<String>observableArrayList(chord));
                final LineChart<Number, String> chart = new LineChart<>(xAxis, yAxis);
                chart.setTitle("harmony");
                for (int i = 0; i < harmony_ans.length; i++) {
                        series.getData().add(
                                        new XYChart.Data<Number, String>(i * shiftDuration, chord[harmony_ans[i]]));
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
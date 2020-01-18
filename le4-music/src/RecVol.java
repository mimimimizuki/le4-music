import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import java.util.stream.Stream;
import java.util.stream.Collectors;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.chart.XYChart;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.layout.Region;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.HelpFormatter;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.util.MathArrays;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;
import jp.ac.kyoto_u.kuis.le4music.Recorder;
import static jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils.verbose;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.LineUnavailableException;
import org.apache.commons.cli.ParseException;

public final class RecVol extends Application {

  private static final Options options = new Options();
  private static final String helpMessage = MethodHandles.lookup().lookupClass().getName() + " [OPTIONS] <WAVFILE>";

  static {
    /* コマンドラインオプション定義 */
    options.addOption("h", "help", false, "display this help and exit");
    options.addOption("v", "verbose", false, "Verbose output");
    options.addOption("m", "mixer", true, "Index of the Mixer object that supplies a SourceDataLine object. "
        + "To check the proper index, use CheckAudioSystem");
    options.addOption("l", "loop", false, "Loop playback");
    options.addOption("f", "frame", true,
        "Frame duration [seconds] " + "(Default: " + Le4MusicUtils.frameDuration + ")");
    options.addOption("i", "interval", true,
        "Frame notification interval [seconds] " + "(Default: " + Le4MusicUtils.frameInterval + ")");
    options.addOption("b", "buffer", true, "Duration of line buffer [seconds]");
    options.addOption("d", "duration", true, "Duration of spectrogram [seconds]");
    options.addOption(null, "amp-lo", true,
        "Lower bound of amplitude [dB] (Default: " + Le4MusicUtils.spectrumAmplitudeLowerBound + ")");
    options.addOption(null, "amp-up", true,
        "Upper bound of amplitude [dB] (Default: " + Le4MusicUtils.spectrumAmplitudeUpperBound + ")");
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
    final double duration = Optional.ofNullable(cmd.getOptionValue("duration")).map(Double::parseDouble)
        .orElse(Le4MusicUtils.spectrogramDuration);
        final double frameDuration = Optional.ofNullable(cmd.getOptionValue("frame")).map(Double::parseDouble)
                                .orElse(Le4MusicUtils.frameDuration);
    final double shiftDuration = Optional.ofNullable(cmd.getOptionValue("shift")).map(Double::parseDouble)
                                .orElse(Le4MusicUtils.frameDuration / 8);

    final double interval = Optional.ofNullable(cmd.getOptionValue("interval")).map(Double::parseDouble)
        .orElse(Le4MusicUtils.frameInterval);

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

    /* データ処理スレッド */
    final ExecutorService executor = Executors.newSingleThreadExecutor();

    /* 窓関数とFFTのサンプル数 */
    final int fftSize = 1 << Le4MusicUtils.nextPow2(recorder.getFrameSize());
    final int fftSize2 = (fftSize >> 1) + 1;

    /* 窓関数を求め，それを正規化する */
    final double[] window = MathArrays.normalizeArray(Le4MusicUtils.hanning(recorder.getFrameSize()), 1.0);

    /* 各フーリエ変換係数に対応する周波数 */
    final double[] freqs = IntStream.range(0, fftSize2).mapToDouble(i -> i * recorder.getSampleRate() / fftSize)
        .toArray();

    /* フレーム数 */
    final int frames = (int) Math.round(duration / interval);

    /* データ系列を作成 */
    final ObservableList<XYChart.Data<Number, Number>> data_vol = IntStream.range(0, 1)
    .mapToObj(i -> new XYChart.Data<Number, Number>(0.5, 0))
    .collect(Collectors.toCollection(FXCollections::observableArrayList));

    /* データ系列に名前をつける */
    final XYChart.Series<Number, Number> series_vol = new XYChart.Series<>(data_vol);
    
    /* 軸を作成 */
    final NumberAxis xAxis = new NumberAxis("", 0.0, 1.0, Le4MusicUtils.autoTickUnit(1));
    xAxis.setAnimated(false);
    final double ampLowerBound =
            Optional.ofNullable(cmd.getOptionValue("amp-lo"))
                    .map(Double::parseDouble)
                    .orElse(Le4MusicUtils.spectrumAmplitudeLowerBound);
        final double ampUpperBound =
            Optional.ofNullable(cmd.getOptionValue("amp-up"))
                    .map(Double::parseDouble)
                    .orElse(Le4MusicUtils.spectrumAmplitudeUpperBound);
        if (ampUpperBound <= ampLowerBound)
            throw new IllegalArgumentException(
                "amp-up must be larger than amp-lo: " +
                "amp-lo = " + ampLowerBound + ", amp-up = " + ampUpperBound
            );
        final NumberAxis yAxis = new NumberAxis(
            /* lowerBound = */ ampLowerBound,
            /* upperBound = */ ampUpperBound,
            /* tickUnit = */ Le4MusicUtils.autoTickUnit(ampUpperBound - ampLowerBound)
        );
        yAxis.setAnimated(false);
        

        final LineChart<Number, Number> vv = new LineChart<>(xAxis, yAxis);
        vv.getData().add(series_vol);
        vv.setAnimated(false);
        vv.setCreateSymbols(false);
        // yAxis.tickLabelsVisible(false);
        vv.getXAxis().setVisible(false);
        vv.getYAxis().setVisible(false);

    
    /* グラフ描画 */
    final Scene scene = new Scene(vv, 100, 600);
    scene.getStylesheets().add("le4music.css");
    primaryStage.setScene(scene);
    primaryStage.setTitle(getClass().getName());
    /* ウインドウを閉じたときに他スレッドも停止させる */
    primaryStage.setOnCloseRequest(req -> executor.shutdown());
    primaryStage.show();
    Platform.setImplicitExit(true);

    recorder.addAudioFrameListener((frame, position) -> Platform.runLater(() -> {
        final double posInSec = position / recorder.getSampleRate();
        final double rms = Arrays.stream(frame).map(x -> x * x).average().orElse(0.0);
        final double logRms = 20.0 * Math.log10(rms);
        XYChart.Data<Number, Number> datum = new XYChart.Data<Number, Number>(0.5,logRms);
        XYChart.Data<Number, Number> datum_0 = new XYChart.Data<Number, Number>(0.5,-100.0);

        data_vol.clear();
        data_vol.addAll(datum, datum_0);

        

      /* 軸を更新 */
//       xAxis.setUpperBound(posInSec);
//       xAxis.setLowerBound(posInSec - duration);
    }));
    recorder.start();

  }

}

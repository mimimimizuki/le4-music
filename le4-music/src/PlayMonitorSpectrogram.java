import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.chart.NumberAxis;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.HelpFormatter;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.util.MathArrays;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;
import jp.ac.kyoto_u.kuis.le4music.Player;
import jp.ac.kyoto_u.kuis.le4music.LineChartWithSpectrogram;
import static jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils.verbose;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.LineUnavailableException;
import org.apache.commons.cli.ParseException;

public final class PlayMonitorSpectrogram extends Application {

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

    /* Player を作成 */
    final Player.Builder builder = Player.builder(wavFile);
    Optional.ofNullable(cmd.getOptionValue("mixer")).map(Integer::parseInt)
        .map(index -> AudioSystem.getMixerInfo()[index]).ifPresent(builder::mixer);
    if (cmd.hasOption("loop"))
      builder.loop();
    Optional.ofNullable(cmd.getOptionValue("buffer")).map(Double::parseDouble).ifPresent(builder::bufferDuration);
    Optional.ofNullable(cmd.getOptionValue("frame")).map(Double::parseDouble).ifPresent(builder::frameDuration);
    builder.interval(interval);
    builder.daemon();
    final Player player = builder.build();

    /* データ処理スレッド */
    final ExecutorService executor = Executors.newSingleThreadExecutor();

    /* 窓関数とFFTのサンプル数 */
    final int fftSize = 1 << Le4MusicUtils.nextPow2(player.getFrameSize());
    final int fftSize2 = (fftSize >> 1) + 1;

    /* 窓関数を求め，それを正規化する */
    final double[] window = MathArrays.normalizeArray(Le4MusicUtils.hanning(player.getFrameSize()), 1.0);

    /* 各フーリエ変換係数に対応する周波数 */
    final double[] freqs = IntStream.range(0, fftSize2).mapToDouble(i -> i * player.getSampleRate() / fftSize)
        .toArray();

    /* フレーム数 */
    final int frames = (int) Math.round(duration / interval);

    /* 軸を作成 */
    final NumberAxis xAxis = new NumberAxis(/* axisLabel = */ "Time (seconds)", /* lowerBound = */ -duration,
        /* upperBound = */ 0, /* tickUnit = */ Le4MusicUtils.autoTickUnit(duration));
    xAxis.setAnimated(false);

    final double freqLowerBound = Optional.ofNullable(cmd.getOptionValue("freq-lo")).map(Double::parseDouble)
        .orElse(0.0);
    if (freqLowerBound < 0.0)
      throw new IllegalArgumentException("freq-lo must be non-negative: " + freqLowerBound);
    final double freqUpperBound = Optional.ofNullable(cmd.getOptionValue("freq-up")).map(Double::parseDouble)
        .orElse(player.getNyquist());
    if (freqUpperBound <= freqLowerBound)
      throw new IllegalArgumentException(
          "freq-up must be larger than freq-lo: " + "freq-lo = " + freqLowerBound + ", freq-up = " + freqUpperBound);
    final NumberAxis yAxis = new NumberAxis(/* axisLabel = */ "Frequency (Hz)", /* lowerBound = */ freqLowerBound,
        /* upperBound = */ freqUpperBound,
        /* tickUnit = */ Le4MusicUtils.autoTickUnit(freqUpperBound - freqLowerBound));
    yAxis.setAnimated(false);

    /* スペクトログラム表示chart */
    final LineChartWithSpectrogram<Number, Number> chart = new LineChartWithSpectrogram<>(xAxis, yAxis);
    chart.setParameters(frames, fftSize2, player.getNyquist());
    chart.setTitle("Spectrogram");

    /* グラフ描画 */
    final Scene scene = new Scene(chart, 800, 600);
    scene.getStylesheets().add("le4music.css");
    primaryStage.setScene(scene);
    primaryStage.setTitle(getClass().getName());
    /* ウインドウを閉じたときに他スレッドも停止させる */
    primaryStage.setOnCloseRequest(req -> executor.shutdown());
    primaryStage.show();
    Platform.setImplicitExit(true);

    player.addAudioFrameListener((frame, position) -> executor.execute(() -> {
      final double[] wframe = MathArrays.ebeMultiply(frame, window);
      final Complex[] spectrum = Le4MusicUtils.rfft(Arrays.copyOf(wframe, fftSize));
      final double posInSec = position / player.getSampleRate();

      /* スペクトログラム描画 */
      chart.addSpectrum(spectrum);

      /* 軸を更新 */
      xAxis.setUpperBound(posInSec);
      xAxis.setLowerBound(posInSec - duration);
    }));

    /* 録音開始 */
    Platform.runLater(player::start);
  }

}

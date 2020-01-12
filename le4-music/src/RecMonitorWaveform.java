import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.chart.XYChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.collections.ObservableList;
import javafx.collections.FXCollections;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.HelpFormatter;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;
import jp.ac.kyoto_u.kuis.le4music.Recorder;
import static jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils.verbose;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.LineUnavailableException;
import org.apache.commons.cli.ParseException;

public final class RecMonitorWaveform extends Application {

  private static final Options options = new Options();
  private static final String helpMessage = MethodHandles.lookup().lookupClass().getName() + " [OPTIONS]";

  static {
    /* コマンドラインオプション定義 */
    options.addOption("h", "help", false, "Display this help and exit");
    options.addOption("v", "verbose", false, "Verbose output");
    options.addOption("m", "mixer", true, "Index of Mixer object that supplies a SourceDataLine object. "
        + "To check the proper index, use CheckAudioSystem");
    options.addOption("o", "outfile", true, "Output file");
    options.addOption("r", "rate", true, "Sampling rate [Hz]");
    options.addOption("f", "frame", true, "Frame duration [seconds]");
    options.addOption("i", "interval", true, "Frame update interval [seconds]");
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

    /* Recorderオブジェクトを生成 */
    final Recorder.Builder builder = Recorder.builder();
    Optional.ofNullable(cmd.getOptionValue("rate")).map(Float::parseFloat).ifPresent(builder::sampleRate);
    Optional.ofNullable(cmd.getOptionValue("mixer")).map(Integer::parseInt)
        .map(index -> AudioSystem.getMixerInfo()[index]).ifPresent(builder::mixer);
    Optional.ofNullable(cmd.getOptionValue("outfile")).map(File::new).ifPresent(builder::wavFile);
    builder.frameDuration(frameDuration);
    Optional.ofNullable(cmd.getOptionValue("interval")).map(Double::parseDouble).ifPresent(builder::interval);
    builder.daemon();
    final Recorder recorder = builder.build();

    /* データ処理スレッド */
    final ExecutorService executor = Executors.newSingleThreadExecutor();

    /* データ系列を作成 */
    final ObservableList<XYChart.Data<Number, Number>> data = IntStream.range(-recorder.getFrameSize(), 0)
        .mapToDouble(i -> i / recorder.getSampleRate()).mapToObj(t -> new XYChart.Data<Number, Number>(t, 0.0))
        .collect(Collectors.toCollection(FXCollections::observableArrayList));

    /* データ系列に名前をつける */
    final XYChart.Series<Number, Number> series = new XYChart.Series<>("Waveform", data);

    /* 波形リアルタイム表示 */
    /* 軸を作成 */
    /* 時間軸（横軸） */
    final NumberAxis xAxis = new NumberAxis(/* axisLabel = */ "Time (seconds)", /* lowerBound = */ -frameDuration,
        /* upperBound = */ 0.0, /* tickUnit = */ Le4MusicUtils.autoTickUnit(frameDuration));
    /* 周波数軸（縦軸） */
    final NumberAxis yAxis = new NumberAxis(/* axisLabel = */ "Amplitude", /* lowerBound = */ -1.0,
        /* upperBound = */ +1.0, /* tickUnit = */ Le4MusicUtils.autoTickUnit(1.0 * 2));

    /* チャートを作成 */
    final LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
    chart.setTitle("Waveform");
    chart.setLegendVisible(false);
    /* データの追加・削除時にアニメーション（フェードイン・アウトなど）しない */
    chart.setAnimated(false);
    /* データアイテムに対してシンボルを作成しない */
    chart.setCreateSymbols(false);

    chart.getData().add(series);

    /* 描画ウインドウ作成 */
    final Scene scene = new Scene(chart, 800, 600);
    scene.getStylesheets().add("le4music.css");
    primaryStage.setScene(scene);
    primaryStage.setTitle(getClass().getName());
    /* ウインドウを閉じたときに他スレッドも停止させる */
    primaryStage.setOnCloseRequest(req -> executor.shutdown());
    primaryStage.show();

    recorder.addAudioFrameListener((frame, position) -> executor.execute(() -> {
      IntStream.range(0, recorder.getFrameSize()).forEach(i -> {
        final XYChart.Data<Number, Number> datum = data.get(i);
        datum.setXValue((i + position - recorder.getFrameSize()) / recorder.getSampleRate());
        datum.setYValue(frame[i]);
      });
      final double posInSec = position / recorder.getSampleRate();
      xAxis.setLowerBound(posInSec - frameDuration);
      xAxis.setUpperBound(posInSec);
    }));

    /* 録音開始 */
    Platform.runLater(recorder::start);
  }

}

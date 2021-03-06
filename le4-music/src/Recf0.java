import java.lang.invoke.MethodHandles;
import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ConcurrentModificationException;
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
import org.apache.commons.math3.complex.Complex;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;
import jp.ac.kyoto_u.kuis.le4music.Player;
import static jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils.verbose;
import jp.ac.kyoto_u.kuis.le4music.LineChartWithSpectrogram;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.LineUnavailableException;
import org.apache.commons.cli.ParseException;
import org.apache.commons.math3.util.MathArrays;

public final class Recf0 extends Application {

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
    public final void start(final Stage primaryStage) throws IOException, UnsupportedAudioFileException,
            LineUnavailableException, ParseException, ConcurrentModificationException {
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

        final double frameDuration = Optional.ofNullable(cmd.getOptionValue("frame")).map(Double::parseDouble)
                .orElse(Le4MusicUtils.frameDuration);
                
        final double duration = 5.0;
        // Optional.ofNullable(cmd.getOptionValue("duration")).map(Double::parseDouble)
        //         .orElse(Le4MusicUtils.spectrogramDuration);
        /* シフトのサンプル数 */
        final double shiftDuration = Optional.ofNullable(cmd.getOptionValue("shift")).map(Double::parseDouble)
                .orElse(Le4MusicUtils.frameDuration / 8);
        
        final double interval = Optional.ofNullable(cmd.getOptionValue("interval")).map(Double::parseDouble)
        .orElse(Le4MusicUtils.frameInterval);
        /* フレーム数 */
    final int frames = (int) Math.round(duration / interval);

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

        final int shiftSize = (int) Math.round(shiftDuration * player.getSampleRate());

        /* データ処理スレッド */
        final ExecutorService executor = Executors.newSingleThreadExecutor();

        /* 窓関数とFFTのサンプル数 */
        final int fftSize = 1 << Le4MusicUtils.nextPow2(player.getFrameSize());
        final int fftSize2 = (fftSize >> 1) + 1;

        /* 窓関数を求め，それを正規化する */
        final double[] window = MathArrays.normalizeArray(Le4MusicUtils.hanning(player.getFrameSize()), 1.0);


        /* データ系列を作成 */
        final ObservableList<XYChart.Data<Number, Number>> data = IntStream.range(-player.getFrameSize(), 0)
                .mapToDouble(i -> i / player.getSampleRate()).mapToObj(t -> new XYChart.Data<Number, Number>(t, 0.0))
                .collect(Collectors.toCollection(FXCollections::observableArrayList));

        /* データ系列に名前をつける */
        final XYChart.Series<Number, Number> series = new XYChart.Series<>("f0", data);

        /* 時間軸（横軸） */
        final NumberAxis xAxis = new NumberAxis(/* axisLabel = */ "Time (seconds)", /* lowerBound = */ -frameDuration,
                /* upperBound = */ 0.0, /* tickUnit = */ Le4MusicUtils.autoTickUnit(frameDuration));
        /* 周波数軸（縦軸） */
        final NumberAxis yAxis = new NumberAxis(/* axisLabel = */ "Freqency (Hz)", /* lowerBound = */ 0.0,
                /* upperBound = */ 600, /* tickUnit = */ Le4MusicUtils.autoTickUnit(600));

        /* チャートを作成 */
        /* スペクトログラム表示chart */
        final LineChartWithSpectrogram<Number, Number> chart = new LineChartWithSpectrogram<>(xAxis, yAxis);
        chart.setParameters(frames, fftSize2, player.getNyquist());
        chart.setTitle("Spectrogram");
        chart.setLegendVisible(false);
        /* データの追加・削除時にアニメーション（フェードイン・アウトなど）しない */
        chart.setAnimated(false);
        /* データアイテムに対してシンボルを作成しない */
        chart.setCreateSymbols(false);

        chart.getData().add(series);
        String[] pitch = new String[] { "ド", "ド#", "レ", "ミ♭", "ミ", "ファ", "ファ#", "ソ", "ソ#", "ラ", "シ♭", "シ" };


        /* 描画ウインドウ作成 */
        final Scene scene = new Scene(chart, 800, 600);
        scene.getStylesheets().add("le4music.css");
        primaryStage.setScene(scene);
        primaryStage.setTitle(getClass().getName());
        /* ウインドウを閉じたときに他スレッドも停止させる */
        primaryStage.setOnCloseRequest(req -> executor.shutdown());
        primaryStage.show();

        player.addAudioFrameListener((frame, position) -> Platform.runLater(() -> {
            final double rms = Arrays.stream(frame).map(x -> x * x).average().orElse(0.0);
            final double logRms = 20.0 * Math.log10(rms);
            final double posInSec = position / player.getSampleRate();
            final double[] wframe = MathArrays.ebeMultiply(frame, window);
            final Complex[] spectrum = Le4MusicUtils.rfft(Arrays.copyOf(wframe, fftSize));

            /* スペクトログラム描画 */
            chart.addSpectrum(spectrum);

            final int fftSize_f0 = 1 << Le4MusicUtils.nextPow2(player.getFrameSize());
            final double[] window_f0 = MathArrays
                    .normalizeArray(Arrays.copyOf(Le4MusicUtils.hanning(player.getFrameSize()), fftSize_f0), 1.0);
            final Stream<Complex[]> spectrogram_f0 = Le4MusicUtils.sliding(frame, window_f0, shiftSize)
                    .map(f -> Le4MusicUtils.rfft(f));
            double[][] specLog_f0 = spectrogram_f0.map(sp -> Arrays.stream(sp).mapToDouble(c -> c.abs()).toArray())
                    .toArray(n -> new double[n][]);
            double[] freq0 = new double[specLog_f0.length];
            for (int m = 0; m < specLog_f0.length; m++) {
                for (int k = 0; k < specLog_f0[m].length; k++) {
                        if (k * player.getSampleRate() / fftSize_f0 > 500.0) {
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
            xAxis.setLowerBound(posInSec - duration);
            xAxis.setUpperBound(posInSec);
            if (posInSec > frameDuration) {
                data.remove(0, 1);
            }
            if (logRms < -100) {
                min = 0;
            }
            try{
                FileWriter fw = new FileWriter("f0.txt");
                if ((position / 320) % 8 == 0) {
                    XYChart.Data<Number, Number> datum = new XYChart.Data<Number, Number>(posInSec,
                                    min * player.getSampleRate() / fftSize_f0);
                    fw.write(String.valueOf(min * player.getSampleRate() / fftSize_f0));
                    data.add(datum);
                    datum = new XYChart.Data<Number, Number>(posInSec + 0.02,
                                    min * player.getSampleRate() / fftSize_f0);
                    fw.write(String.valueOf(min * player.getSampleRate() / fftSize_f0));
                    data.add(datum);
                    datum = new XYChart.Data<Number, Number>(posInSec + 0.04,
                                    min * player.getSampleRate() / fftSize_f0);
                    fw.write(String.valueOf(min * player.getSampleRate() / fftSize_f0));
                    data.add(datum);
                    datum = new XYChart.Data<Number, Number>(posInSec + 0.06,
                                    min * player.getSampleRate() / fftSize_f0);
                    fw.write(String.valueOf(min * player.getSampleRate() / fftSize_f0));
                    data.add(datum);
                    datum = new XYChart.Data<Number, Number>(posInSec + 0.08,
                                    min * player.getSampleRate() / fftSize_f0);
                    fw.write(String.valueOf(min * player.getSampleRate() / fftSize_f0));
                    data.add(datum);
                    datum = new XYChart.Data<Number, Number>(posInSec + 0.10,
                                    min * player.getSampleRate() / fftSize_f0);
                    fw.write(String.valueOf(min * player.getSampleRate() / fftSize_f0));
                    data.add(datum);
                    datum = new XYChart.Data<Number, Number>(posInSec + 0.12,
                                    min * player.getSampleRate() / fftSize_f0);
                    fw.write(String.valueOf(min * player.getSampleRate() / fftSize_f0));
                    data.add(datum);
                    datum = new XYChart.Data<Number, Number>(posInSec + 0.14,
                                    min * player.getSampleRate() / fftSize_f0);
                    fw.write(String.valueOf(min * player.getSampleRate() / fftSize_f0));
                    data.add(datum);
                    int noteNum = (int) Math.round(
                                    Le4MusicUtils.hz2nn(min * player.getSampleRate() / fftSize_f0));
                    System.out.println(pitch[noteNum % 12]);
                }
                if(posInSec == 273){
                        fw.close();
                    }
        }
                catch(IOException ex) {
                        ex.printStackTrace();
                }
            
            

        }));
        /* 録音開始 */
    Platform.runLater(player::start);
    }

}

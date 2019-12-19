import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.imageio.ImageIO;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.chart.XYChart;
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

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;
import jp.ac.kyoto_u.kuis.le4music.LineChartWithSpectrogram;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.apache.commons.cli.ParseException;
import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.util.MathArrays;

public final class Plotf0CLI extends Application {

    private static final Options options = new Options();
    private static final String helpMessage =
        MethodHandles.lookup().lookupClass().getName()+" [OPTIONS] <WAVFILE>";

    static {
        /* コマンドラインオプション定義*/
        options.addOption("h", "help", false, "Display this help and exit");
        options.addOption("o", "outfile", true,
                          "Output image file (Default: " +
                          MethodHandles.lookup().lookupClass().getSimpleName() +
                          "." + Le4MusicUtils.outputImageExt + ")");
        options.addOption("a", "amp-bounds", true,
                          "Upper(+) and lower(-) bounds in the amplitude direction " +
                          "(Default: " + Le4MusicUtils.waveformAmplitudeBounds + ")");
    }

   @Override
    public final void start(final Stage primaryStage)
        throws IOException,
                UnsupportedAudioFileException,
                ParseException {
            /* コマンドライン引数処理*/
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

        /* W A V ファイル読み込み*/
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
        final int shiftSize = (int) Math.round(shiftDuration * sampleRate);

        /* 窓関数を求め， それを正規化する */
        final double[] window = MathArrays.normalizeArray(Arrays.copyOf(Le4MusicUtils.hanning(frameSize), fftSize),
                1.0);

        /* 短時間フーリエ変換本体 */
        final Stream<Complex[]> spectrogram = Le4MusicUtils.sliding(waveform, window, shiftSize)
                .map(frame -> Le4MusicUtils.rfft(frame));

        /* 複素スペクトログラムを対数振幅スペクトログラムに */
        double[][] specLog = spectrogram.map(sp -> Arrays.stream(sp).mapToDouble(c -> c.abs()).toArray())
                .toArray(n -> new double[n][]);
        
        /* 短時間フーリエ変換本体 */
        final Stream<Complex[]> spectrogram1 = Le4MusicUtils.sliding(waveform, window, shiftSize)
                .map(frame -> Le4MusicUtils.rfft(frame));

        /* 複素スペクトログラムを対数振幅スペクトログラムに */
        final double[][] specLog1 = spectrogram1.map(sp -> Arrays.stream(sp).mapToDouble(c -> 20.0 * Math.log10(c.abs())).toArray())
                .toArray(n -> new double[n][]);
        
        final Stream<Complex[]> spectrogram2 = Le4MusicUtils.sliding(waveform, window, shiftSize)
                .map(frame -> Le4MusicUtils.rfft(frame));

        /* 複素スペクトログラムを対数振幅スペクトログラムに */
        double[][] specLog2 = spectrogram2.map(sp -> Arrays.stream(sp).mapToDouble(c -> c.getReal()).toArray())
                .toArray(n -> new double[n][]);

        
        /* 参考： フレーム数と各フレーム先頭位置の時刻 */
        final double[] times = IntStream.range(0, specLog.length).mapToDouble(i -> i * shiftDuration).toArray();
        
        /* 参考： 各フーリエ変換係数に対応する周波数 */
        final double[] freqs = IntStream.range(0, fftSize2).mapToDouble(i -> i * sampleRate / fftSize).toArray();
        System.out.println(times.length);
        
        double[] f0 = new double [times.length];
        double[] new_freq = new double [times.length];
        final double lowerf0 = Le4MusicUtils.f0LowerBound;
        final double upperf0 = 400;
        
        for (int i = 0; i < times.length; i++) {
    		// specLog[i][j]が振幅
    		for (int j = 0; j < specLog[i].length; j++) {
    			if(j * sampleRate / fftSize < upperf0 && j * sampleRate / fftSize > lowerf0 && specLog[i][j] > f0[i]) {
    				f0[i] = specLog[i][j]; 
    				new_freq[i] = j;
    			}
    		}
        }
        System.out.print(shiftSize);
        System.out.print(frameSize);
        int pointer = 0;
        for(int m = 0; m < waveform.length-1-frameSize; m+=shiftSize) {
        	int zero_counter = 0;
        	for(int n = m; n < m + frameSize-1; n++) {
        		if (waveform[n] * waveform[n+1] < 0) {
            		zero_counter += 1;
        		}
        	}
        	if(zero_counter > 2 * new_freq[pointer]) {
        		new_freq[pointer] = 0;
        	}
        	pointer++;
        }


        /* データ系列を作成*/
        final ObservableList<XYChart.Data<Number, Number>> data =
            IntStream.range(0, f0.length)
                    .mapToObj(i -> new XYChart.Data<Number, Number>(i * shiftDuration, new_freq[i] * sampleRate / fftSize))
                    .collect(Collectors.toCollection(FXCollections::observableArrayList));

        /* データ系列に名前をつける*/
        final XYChart.Series<Number, Number> series =
            new XYChart.Series<>("Waveform", data);

        
        /* X 軸を作成*/
        final double duration = (waveform.length - 1) / sampleRate;
        final NumberAxis xAxis = new NumberAxis(
            /* axisLabel = */ "Time (seconds)",
            /* lowerBound = */ 0.0,
            /* upperBound = */ duration,
            /* tickUnit = */ Le4MusicUtils.autoTickUnit(duration)
        );
        xAxis.setAnimated(false);

        /* Y 軸を作成*/
        final NumberAxis yAxis = new NumberAxis(/* axisLabel = */ "Frequency (Hz)", /* lowerBound = */ 0.0,
                /* upperBound = */ 600, /* tickUnit = */ Le4MusicUtils.autoTickUnit(600));
        yAxis.setAnimated(false);

        /* チャートを作成*/
        final LineChartWithSpectrogram<Number, Number> chart = new LineChartWithSpectrogram<>(xAxis, yAxis);
        chart.setParameters(specLog.length, fftSize2, nyquist);
        Arrays.stream(specLog1).forEach(chart::addSpecLog);
        chart.setTitle("f0");
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        chart.getData().add(series);

        /* グラフ描画*/
        final Scene scene = new Scene(chart, 800, 600);
        scene.getStylesheets().add("le4music.css");

        /* ウインドウ表示*/
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
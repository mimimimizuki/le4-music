import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.Arrays;
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

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.util.MathArrays;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.apache.commons.cli.ParseException;

public final class PlotJudgeCLI extends Application {

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
        options.addOption(null, "amp-lo", true,
                          "Lower bound of amplitude [dB] (Default: " +
                          Le4MusicUtils.spectrumAmplitudeLowerBound + ")");
        options.addOption(null, "amp-up", true,
                          "Upper bound of amplitude [dB] (Default: " +
                          Le4MusicUtils.spectrumAmplitudeUpperBound + ")");
        options.addOption(null, "freq-lo", true,
                          "Lower bound of frequency [Hz] (Default: 0.0)");
        options.addOption(null, "freq-up", true,
                          "Upper bound of frequency [Hz] (Default: Nyquist)");
    }

    @Override public final void start(final Stage primaryStage)
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

        /* fftSize = 2ˆp >= waveform.length を満たすfftSize を求める
        * 2ˆp はシフト演算で求める*/
        final double frameDuration = Optional.ofNullable(cmd.getOptionValue("frame")).map(Double::parseDouble)
                .orElse(Le4MusicUtils.frameDuration);
        final int frameSize = (int) Math.round(frameDuration * sampleRate);
        final int fftSize = 1 << Le4MusicUtils.nextPow2(waveform.length);
        final int fftSize2 = (fftSize >> 1) + 1;
        /* 信号の長さをfftSize に伸ばし， 長さが足りない部分は0 で埋める．
        * 振幅を信号長で正規化する． */
        
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
        
        /* 参考： フレーム数と各フレーム先頭位置の時刻 */
        final double[] times = IntStream.range(0, specLog.length).mapToDouble(i -> i * shiftDuration).toArray();
        
        /* 参考： 各フーリエ変換係数に対応する周波数 */
        final double[] freqs = IntStream.range(0, fftSize2).mapToDouble(i -> i * sampleRate / fftSize).toArray();
        System.out.println(times.length);

        Complex[][] cepstrum = new Complex[times.length][];
        final double[][] ceps = new double[times.length][];
        for(int i = 0; i< times.length; i++) {
        	int fftSize_c = 1 << Le4MusicUtils.nextPow2(specLog[i].length);
            int fftSize2_c = (fftSize_c >> 1) + 1;
            double[] src = Arrays.stream(Arrays.copyOf(specLog[i], fftSize_c)).toArray();
        	cepstrum[i] = Le4MusicUtils.fft(src);
        	ceps[i] = new double[13];
        	for(int j = 0; j < 13; j++) {
        		ceps[i][j] = cepstrum[i][j].getReal();
        	}//これを１３こだけ使う予定
        }
        double[][] average = new double[5][13];
        average[0][0] = 53.0;
        average[0][1] = 45.0;
        average[0][2] = 33.0;
        average[0][3] = 26.0;
        average[0][4] = 19.0;
        average[0][5] = 13.0;
        average[0][6] = 6.0;
        average[0][7] = 0.0;
        average[0][8] = 0.0;
        average[0][9] = 0.0;
        average[0][10] = 1.0;
        average[0][11] = 3.0;
        average[0][12] = 4.0;
        

        /* スペクトル配列の各要素に対応する周波数を求める．
        * 以下を満たすように線型に
        * freqs[0] = 0Hz
        * freqs[fftSize2 - 1] = sampleRate / 2 (= Nyquist周波数) */

        final double[] freqs =
            IntStream.range(0, fftSize2)
                     .mapToDouble(i -> i * sampleRate / fftSize)
                     .toArray();

        final int fftSize_ceps = 1 << Le4MusicUtils.nextPow2(cepstrum.length);
        final int fftSize2_ceps = (fftSize_ceps >> 1) + 1;
        final Complex[] ceceps = new Complex[fftSize2_ceps];
        for (int j = 0; j<ceceps.length; j++) {
        	if(j >= 13) {
        		ceceps[j] = new Complex(0,0);
        	}
        	else {
        		ceceps[j] = cepstrum[j];
        	}
        }
  
        final double[] cepsLog = Le4MusicUtils.irfft(ceceps);


        /* データ系列を作成*/
        final ObservableList<XYChart.Data<Number, Number>> data =
            IntStream.range(0, fftSize2)
                     .mapToObj(i -> new XYChart.Data<Number, Number>(freqs[i], specLog[i]))
                     .collect(Collectors.toCollection(FXCollections::observableArrayList));
        final ObservableList<XYChart.Data<Number, Number>> data1 =
                IntStream.range(0, fftSize2)
                .mapToObj(i -> new XYChart.Data<Number, Number>(freqs[i], cepsLog[i]))
                .collect(Collectors.toCollection(FXCollections::observableArrayList));

        /* データ系列に名前をつける*/
        final XYChart.Series<Number, Number> series =
        new XYChart.Series<>("spectrum", data);
        
        final XYChart.Series<Number, Number> series1 =
                new XYChart.Series<>("spectrum", data1);

        /* X 軸を作成*/
        final double freqLowerBound =
            Optional.ofNullable(cmd.getOptionValue("freq-lo"))
                    .map(Double::parseDouble)
                    .orElse(0.0);
        if (freqLowerBound < 0.0)
            throw new IllegalArgumentException(
                "freq-lo must be non-negative: " + freqLowerBound
            );
        final double freqUpperBound =
            Optional.ofNullable(cmd.getOptionValue("freq-up"))
                    .map(Double::parseDouble)
                    .orElse(nyquist);
        if (freqUpperBound <= freqLowerBound)
            throw new IllegalArgumentException(
                "freq-up must be larger than freq-lo: " +
                "freq-lo = " + freqLowerBound + ", freq-up = " + freqUpperBound
            );
        final NumberAxis xAxis = new NumberAxis(
            /* axisLabel = */ "Frequency (Hz)",
            /* lowerBound = */ freqLowerBound,
            /* upperBound = */ freqUpperBound / 3,
            /* tickUnit = */ Le4MusicUtils.autoTickUnit(freqUpperBound / 3 - freqLowerBound)
        );
        xAxis.setAnimated(false);

        /* Y 軸を作成*/
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
            /* axisLabel = */ "Amplitude (dB)",

            /* lowerBound = */ ampLowerBound,
            /* upperBound = */ ampUpperBound + 50,
            /* tickUnit = */ Le4MusicUtils.autoTickUnit(ampUpperBound - ampLowerBound)
        );
        yAxis.setAnimated(false);

        /* チャートを作成*/
        final LineChart<Number, Number> chart =
            new LineChart<>(xAxis, yAxis);
        chart.setTitle("Cepstrum" + " " + pargs[0]);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        chart.getData().add(series);
        chart.getData().add(series1);

        /* グラフ描画*/
        final Scene scene = new Scene(chart, 800, 600);
        scene.getStylesheets().add("src/le4music.css");

        /* ウインドウ表示*/
        primaryStage.setScene(scene);
        primaryStage.setTitle(getClass().getName());
        primaryStage.show();

        /* チャートを画像ファイルへ出力*/
        Platform.runLater(() -> {
            final String[] name_ext = Le4MusicUtils.getFilenameWithImageExt(
                Optional.ofNullable(cmd.getOptionValue("outfile")),
                getClass().getSimpleName()
            );
            final WritableImage image = scene.snapshot(null);
            try {
                ImageIO.write(SwingFXUtils.fromFXImage(image, null),
                name_ext[1], new File(name_ext[0] + "." + name_ext[1]));
            } catch (IOException e) { e.printStackTrace(); }
        });
    }

}
import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.IntStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.imageio.ImageIO;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
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

public final class PlotchromaVCLI extends Application {

    private static final Options options = new Options();
    private static final String helpMessage = MethodHandles.lookup().lookupClass().getName() + " [OPTIONS] <WAVFILE>";

    static {
        /* コマンドラインオプション定義 */
        options.addOption("h", "help", false, "Display this help and exit");
        options.addOption("o", "outfile", true, "Output image file (Default: "
                + MethodHandles.lookup().lookupClass().getSimpleName() + "." + Le4MusicUtils.outputImageExt + ")");
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
        final int shiftSize = (int) Math.round(shiftDuration * sampleRate);

        /* 窓関数を求め， それを正規化する */
        final double[] window = MathArrays.normalizeArray(Arrays.copyOf(Le4MusicUtils.hanning(frameSize), fftSize),
                1.0);

        /* 短時間フーリエ変換本体 */
        final Stream<Complex[]> spectrogram = Le4MusicUtils.sliding(waveform, window, shiftSize)
                .map(frame -> Le4MusicUtils.rfft(frame));

        /* 複素スペクトログラムを対数振幅スペクトログラムに */
        final double[][] specLog = spectrogram.map(sp -> Arrays.stream(sp).mapToDouble(c -> 20.0 * Math.log10(c.abs())).toArray())
                .toArray(n -> new double[n][]);

        /* 参考： フレーム数と各フレーム先頭位置の時刻 */
        final double[] times = IntStream.range(0, specLog.length).mapToDouble(i -> i * shiftDuration).toArray();
        
        final double[] src =
                Arrays.stream(Arrays.copyOf(waveform, fftSize))
                      .map(w -> w / waveform.length)
                      .toArray();
            /* 高速フーリエ変換を行う*/
            final Complex[] spectrum = Le4MusicUtils.rfft(src);

            /* 対数振幅スペクトルを求める*/
            final double[] specLog1 =
                Arrays.stream(spectrum)
                      .mapToDouble(c -> c.abs())
                      .toArray();

        /* 参考： 各フーリエ変換係数に対応する周波数 */
        final double[] freqs = IntStream.range(0, fftSize2).mapToDouble(i -> i * sampleRate / fftSize).toArray();
        final double[][] chord = new double[times.length][3];
        for(int i = 0; i < times.length; i ++) {
        	double max = 0;
        	int[] index = new int[3];
        	for(int j = 0; j < specLog[i].length; j++ ) {
        		if(max < specLog[i][j]) {
        			max = specLog[i][j];
        			index[0] = j;
        		}
        	}
        	max = 0;
        	for(int j = 0; j < specLog[i].length; j++ ) {
        		if(max < specLog[i][j] && j != index[0]) {
        			max = specLog[i][j];
        			index[1] = j;
        		}
        	}
        	max = 0;
        	for(int j = 0; j < specLog[i].length; j++ ) {
        		if(max < specLog[i][j] && j != index[1] && j != index[0]) {
        			max = specLog[i][j];
        			index[2] = j;
        		}
        	}
        	
        }
        double[] CV = new double[23];
        double[] sum = new double[times.length];
        for(int i = 0; i < 23; i ++) {
        	for(int n = 0; n < times.length; n++) {
        		for(int j = 0; j < specLog[n].length; j++) {
        			for(int ch = i; ch < 127; ch += 12) {
        				if(440 + Math.pow(2,( ch - 69) / 12) == j * sampleRate / fftSize) {
            				sum[n] += Math.abs(specLog[n][j]); 
            			}	
        			}
        			
        		}
        		CV[i] = sum[n] / times.length; //各フレームでのピッチのパワーをもとめる すべてのフレームに対して求めて平均
        	}
        }
        
        
        
        

        /* X 軸を作成 */
        final double duration = (specLog.length - 1) * shiftDuration;

        final NumberAxis xAxis = new NumberAxis(/* axisLabel = */ "Time (seconds)", /* lowerBound = */ 0.0,
                /* upperBound = */ duration, /* tickUnit = */ Le4MusicUtils.autoTickUnit(duration));
        xAxis.setAnimated(false);

        /* Y 軸を作成 */
        final NumberAxis yAxis = new NumberAxis(/* axisLabel = */ "Frequency (Hz)", /* lowerBound = */ 0.0,
                /* upperBound = */ 500, /* tickUnit = */ Le4MusicUtils.autoTickUnit(500));
        yAxis.setAnimated(false);

        /* チャートを作成 */
        final LineChartWithSpectrogram<Number, Number> chart = new LineChartWithSpectrogram<>(xAxis, yAxis);
        chart.setParameters(specLog.length, fftSize2, nyquist);
        chart.setTitle("Spectrogram");
        Arrays.stream(specLog).forEach(chart::addSpecLog);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);

        /* グラフ描画 */
        final Scene scene = new Scene(chart, 800, 600);
        scene.getStylesheets().add("src/le4music.css");

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

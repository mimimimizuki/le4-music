import java.io.File;
import java.io.ByteArrayInputStream;

import java.util.Arrays;
import java.util.Map;
import java.util.NavigableMap;
import java.util.stream.Stream;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.Executors;

//import org.jfree.chart.JFreeChart;
//import org.jfree.chart.ChartPanel;

import org.apache.commons.math3.util.MathArrays;
import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.TransformType;

import java.io.IOException;
import java.nio.*;
import javax.sound.sampled.*;

/** 音響信号処理に便利なstaticメソッドを提供するクラスです．  */
public final class Le4MusicUtils {

  private Le4MusicUtils() {
    throw new AssertionError("this class should not be instantiated");
  }

  /** 
   * コマンドラインオプション "-v" や "--verbose" 処理用．
   * 使うときは static import 推奨
   */
  public static boolean verbose = false;

  /** 録音時のサンプリング周波数のデフォルト値 (Hz) */
  public static final double sampleRate = 16000.0;

  /** 短時間分析におけるフレームの長さのデフォルト値（秒） */
  public static final double frameDuration = 0.4;

  /** 短時間分析におけるフレームのシフト長のデフォルト値（秒） */
  public static final double shiftDuration = 0.05;

  private static final FastFourierTransformer transformer =
    new FastFourierTransformer(DftNormalization.STANDARD);

  /**
   * 与えられた数nに対して 2<sup>p</sup> ≧ n を満たす最小のpを求める．
   * 例えば {@code nextPow2(15) = 4}, {@code nextPow2(16) = 4},
   * {@code nextPow2(17) = 5} となる．
   *
   * @param n 整数
   * @return 2<sup>p</sup> ≧ n を満たす最小のp 
   */
  public static final int nextPow2(final int n) {
    if (n <= 0)
      return 0;
    else
      return highestOneBit(n - 1);
  }

  /**
   * 与えられた数nのビットが1である最大の桁を求める．
   * 例えば {@code highestOneBit(2) = 2}, {@code highestOneBit(3) = 2},
   * {@code highestOneBit(4) = 3} となる．
   *
   * @param n 整数
   * @return nのうちビットが1である最大の桁
   */
  public static final int highestOneBit(int n) {
    int digit = Integer.SIZE;
    while (digit > 0) {
      if ((n & Integer.MIN_VALUE) == Integer.MIN_VALUE)
        return digit;
      n = n << 1;
      digit -= 1;
    }
    return digit;
  }

  /**
   * 窓関数を用いた配列の切り出しを行う．
   * 戻り値は {@link java.util.stream.Stream} インタフェースを実装するため，
   * 切り出し処理そのものは遅延評価される．
   *
   * @param x 切り出される配列
   * @param windowSize 窓関数の長さ
   * @param shiftSize 窓関数のシフト長
   * @return 切り出された配列のストリーム
   */
  public static final Stream<double[]> sliding(
    final double[] x,
    final int windowSize,
    final int shiftSize
  ) {
    final int nFrames = x.length / shiftSize;
    return IntStream.range(0, nFrames).mapToObj(i -> {
      final int from = i * shiftSize;
      return Arrays.copyOfRange(x, from, from + windowSize);
    });
  }

  /**
   * 窓関数を用いた配列の切り出しを行う．
   * 戻り値は {@link java.util.stream.Stream} インタフェースを実装するため，
   * 切り出し処理そのものは遅延評価される．
   *
   * @param x 切り出される配列
   * @param window 窓関数
   * @param shiftSize 窓関数のシフト長
   * @return 切り出された配列のストリーム
   */
  public static final Stream<double[]> sliding(
    final double[] x,
    final double[] window,
    final int shiftSize
  ) {
    final int nFrames = x.length / shiftSize;
    return IntStream.range(0, nFrames).mapToObj(i -> {
      final int from = i * shiftSize;
      final double[] frame =
        Arrays.copyOfRange(x, from, from + window.length);
      return MathArrays.ebeMultiply(frame, window);
    });
  }

  /**
   * 入力複素配列をフーリエ変換する．
   * 配列長は2のべき乗でなければならない．
   *
   * @param src 入力複素配列
   * @return 入力配列のフーリエ変換である複素配列．配列長は入力と等しい
   * @throws IllegalArgumentException 配列長が2のべき乗でないとき
   */
  public static final Complex[] fft(final Complex[] src) {
    /* 配列の長さが2のべき乗かどうかチェック */
    if (Integer.bitCount(src.length) != 1)
      throw new IllegalArgumentException("src.length must be power of 2");
    return transformer.transform(src, TransformType.FORWARD);
  }

  /**
   * 入力実配列をフーリエ変換する．
   * 配列長は2のべき乗でなければならない．
   * このメソッドは実部が入力配列，虚部が0の複素配列を引数とした
   * {@link #fft(Complex[])} と等価である．
   *
   * @param src 入力実配列
   * @return 入力配列のフーリエ変換である複素配列．配列長は入力と等しい
   * @throws IllegalArgumentException 配列長が2のべき乗でないとき
   */
  public static final Complex[] fft(final double[] src) {
    /* 配列の長さが2のべき乗かどうかチェック */
    if (Integer.bitCount(src.length) != 1)
      throw new IllegalArgumentException("src.length must be power of 2");
    return transformer.transform(src, TransformType.FORWARD);
  }

  /**
   * 入力複素配列を逆フーリエ変換する．
   * 配列長は2のべき乗でなければならない．
   *
   * @param src 入力複素配列
   * @return 入力配列の逆フーリエ変換である複素配列．配列長は入力と等しい
   * @throws IllegalArgumentException 配列長が2のべき乗でないとき
   */
  public static final Complex[] ifft(final Complex[] src) {
    /* 配列の長さが2のべき乗かどうかチェック */
    if (Integer.bitCount(src.length) != 1)
      throw new IllegalArgumentException("src.length must be power of 2");
    return transformer.transform(src, TransformType.INVERSE);
  }

  /**
   * 入力実配列を逆フーリエ変換する．
   * 配列長は2のべき乗でなければならない．
   * このメソッドは実部が入力配列，虚部が0の複素配列を引数とした
   * {@link #fft(Complex[])} の実行と等価である．
   *
   * @param src 入力実配列
   * @return 入力配列の逆フーリエ変換である複素配列．配列長は入力と等しい
   * @throws IllegalArgumentException 配列長が2のべき乗でないとき
   */
  public static final Complex[] ifft(final double[] src) {
    /* 配列の長さが2のn乗かどうかチェック */
    if (Integer.bitCount(src.length) != 1)
      throw new IllegalArgumentException("src.length must be power of 2");
    return transformer.transform(src, TransformType.INVERSE);
  }

  /**
   * 入力実配列（長さ 2<sup>n</sup>）をフーリエ変換し，
   * 複素配列（長さ 2<sup>n-1</sup>+1）を返す．
   * 実配列のフーリエ変換は対称（複素共役）配列，という性質に基づく．
   *
   * @param src 入力実配列
   * @return 入力配列のフーリエ変換である複素配列
   * @throws IllegalArgumentException 配列長が2のべき乗でないとき
   */
  public static final Complex[] rfft(final double[] src) {
    /* 配列の長さチェック */
    if (Integer.bitCount(src.length) != 1)
      throw new IllegalArgumentException("src.length must be power of 2");
    return Arrays.copyOf(transformer.transform(src, TransformType.FORWARD),
                         (src.length >> 1) + 1);
  }

  /**
   * 入力複素配列（長さ 2<sup>n-1</sup>+1）をフーリエ変換し，
   * 実配列（長さ 2<sup>n</sup>）を返す．
   * 実配列のフーリエ変換は対称（複素共役）配列，という性質に基づく．
   *
   * @param src 入力複素配列
   * @return 入力配列の逆フーリエ変換である実配列
   * @throws IllegalArgumentException 配列長が2のべき乗でないとき
   */
  public static final double[] irfft(final Complex[] src) {
    /* 配列の長さチェック */
    if (Integer.bitCount(src.length - 1) != 1)
      throw new IllegalArgumentException("src.length must be 2^n + 1");
    final int fftSize = (src.length - 1) << 1;
    final Complex[] src0 = Arrays.copyOf(src, fftSize);
    Arrays.fill(src0, src.length, fftSize, Complex.ZERO);
    return Arrays.stream(transformer.transform(src0, TransformType.INVERSE))
                 .mapToDouble(c -> c.getReal())
                 .toArray();
  }

  /**
   * 与えられた長さの矩形窓 (rectangular window) を返す．
   *
   * @param size 窓の長さ
   * @return 矩形窓
   */
  public static final double[] rectangle(final int size) {
    final double[] window = new double[size];
    for (int i = 0; i < size; i++)
      window[i] = 1.0;
    return window;
  }

  /**
   * 与えられた長さのハン窓 (hann window) を返す．
   *
   * @param size 窓の長さ
   * @return ハン窓
   */
  public static final double[] hanning(final int size) {
    final double[] window = new double[size];
    for (int i = 0; i < size; i++)
      window[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / size);
    return window;
  }

  /**
   * 与えられた長さのハミング窓 (hamming window) を返す．
   *
   * @param size 窓の長さ
   * @return ハミング窓
   */
  public static final double[] hamming(final int size) {
    final double[] window = new double[size];
    for (int i = 0; i < size; i++)
      window[i] = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / size);
    return window;
  }

  /**
   * 与えられた長さおよび範囲（標準偏差の何倍か）をもつガウス窓を返す．
   *
   * @param size 窓の長さ
   * @param sigmas ガウス関数の範囲
   * @return ガウス窓
   */
  public static final double[] gaussian(final int size, final double sigmas) {
    final double[] window = new double[size];
    final double center = 0.5 * size;
    for (int i = 0; i < size; i++) {
      final double x = (i - center) / center * sigmas;
      window[i] = Math.exp(-0.5 * x * x);
    }
    return window;
  }

  /**
   * 与えられた長さをもつガウス窓を返す．
   * このメソッドは
   * {@link #gaussian(int, double) gaussian(size, 3.0)} と等価である．
   *
   * @param size 窓の長さ
   * @return ガウス窓
   */
  public static final double[] gaussian(final int size) {
    return gaussian(size, 3.0);
  }

  /**
   * 与えられた長さおよび範囲（標準偏差の何倍か）をもつ
   * ブラックマンハリス窓を返す．
   *
   * @param size 窓の長さ
   * @return ブラックマンハリス窓
   */
  public static final double[] blackmanharris(final int size) {
    final double[] window = new double[size];
    final double a0 = 0.35875;
    final double a1 = 0.48829;
    final double a2 = 0.14128;
    final double a3 = 0.01168;
    for (int i = 0; i < size; i++) {
      final double x = (double)i / size;
      window[i] = a0
                - a1 * Math.cos(2.0 * x)
                + a2 * Math.cos(4.0 * x)
                - a3 * Math.cos(6.0 * x);
    }
    return window;
  }

  /**
   * MIDIノートナンバーをヘルツ単位の周波数に変換する．
   *
   * @param nn MIDIノートナンバー
   * @return ヘルツ単位の周波数
   */
  public static final double nn2hz(final double nn) {
    return 440.0 * Math.pow(2.0, (nn - 69.0) / 12.0);
  }

  /**
   * ヘルツ単位の周波数をMIDIノートナンバーに変換する．
   *
   * @param hz 周波数
   * @return MIDIノートナンバー
   */
  public static final double hz2nn(final double hz) {
    return log2(hz / 440.0) * 12.0 + 69.0;
  }

  private static final double LOG_OF_2 = Math.log(2.0);

  /**
   * {@code double}値の2を底とする対数を返す。
   *
   * @param x 値
   * @return {@code x}の2を底とする対数
   */
  public static final double log2(final double x) {
    return Math.log(x) / LOG_OF_2;
  }

  /**
   * 配列中の最大値のインデックスを返す．
   *
   * @param a 配列
   * @return 配列中の最大値のインデックス
   */
  public static final int argmax(final double[] a) {
    if (a.length == 0) return -1;
    int maxIndex = 0;
    double maxValue = a[0];
    for (int i = 1; i < a.length; i++) {
      if (maxValue < a[i]) {
        maxIndex = i;
        maxValue = a[i];
      }
    }
    return maxIndex;
  }

  /**
   * 最近傍の値をキーに持つエントリを返す．
   *
   * @param <T> エントリの値の型
   * @param nmap Double値をキーにもつマップ
   * @param key 探索キー
   * @return 探索キーに最も近い値をキーにもつエントリ
   */
  public static final <T> Map.Entry<Double, T> nearest(
    final NavigableMap<Double, T> nmap,
    final double key
  ) {
    final Map.Entry<Double, T> floor = nmap.floorEntry(key);
    final Map.Entry<Double, T> ceiling = nmap.ceilingEntry(key);
    if (floor.equals(ceiling)) return floor;
    else if (ceiling == null) return floor;
    else if (floor == null) return ceiling;
    else if (key - floor.getKey() <= ceiling.getKey() - key) return floor;
    else return ceiling;
  }

  /**
   * 与えられたファイル名から
   * {@link javax.sound.sampled.AudioInputStream} を生成して返す．
   * このメソッドは非推奨です。
   * 代わりに {@link AudioSystem#getAudioInputStream(File)} を使用してください。
   *
   * @param audioFilename ファイル名
   * @return ファイル名で指定されるファイルに対する {@code AudioInputStream}
   * @throws UnsupportedAudioFileException サポートされないフォーマットのオーディオファイルが指定された場合
   * @throws IOException 入出力例外が発生した場合
   */
  @Deprecated
  public static final AudioInputStream getAudioInputStream(final String audioFilename)
    throws UnsupportedAudioFileException, IOException {
    final File audioFile = new File(audioFilename);
    final AudioInputStream stream = AudioSystem.getAudioInputStream(audioFile);
    return stream;
  }

  /**
   * AudioInputStreamからデータを読み込んでデコードし，
   * チャネルごとの波形が格納された配列を返す．
   *
   * @param stream ストリーム
   * @return チャネルごとの波形が格納された配列
   * @throws IOException 入出力例外が発生した場合
   */
  public static final double[][] readWaveform(final AudioInputStream stream)
    throws IOException {
    final AudioFormat format = stream.getFormat();
    final double sampleRate = format.getSampleRate();

    final int samples = (int)stream.getFrameLength();
    final int channels = format.getChannels();
    final AudioFormat.Encoding encoding = format.getEncoding();
    final int quantizationBits = format.getSampleSizeInBits();
    final ByteOrder endian = format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    final double[][] waveform = new double[channels][samples];

    if (encoding == AudioFormat.Encoding.PCM_UNSIGNED && quantizationBits == 8) {
      final byte[] buf = new byte[1];
      for (int i = 0; i < samples; i++)
        for (int j = 0; j < channels; j++) {
          IOMisc.readFully(stream, buf);
          waveform[j][i] = unsignedByte2Double(buf[0]);
        }
    } else if (encoding == AudioFormat.Encoding.PCM_SIGNED && quantizationBits == 16) {
      final byte[] buf = new byte[2];
      final ShortBuffer sbuf = ByteBuffer.wrap(buf).order(endian).asShortBuffer();
      for (int i = 0; i < samples; i++)
        for (int j = 0; j < channels; j++) {
          IOMisc.readFully(stream, buf);
          waveform[j][i] = short2Double(sbuf.get(0));
        }      
    } else if (encoding == AudioFormat.Encoding.PCM_SIGNED && quantizationBits == 24) {
      final byte[] buf = new byte[3];
      for (int i = 0; i < samples; i++)
        for (int j = 0; j < channels; j++) {
          IOMisc.readFully(stream, buf);
          waveform[j][i] = signed3Bytes2Double(buf);
        }      
    } else if (encoding == AudioFormat.Encoding.PCM_FLOAT && quantizationBits == 32) {
      final byte[] buf = new byte[4];
      final FloatBuffer fbuf = ByteBuffer.wrap(buf).order(endian).asFloatBuffer();
      for (int i = 0; i < samples; i++)
        for (int j = 0; j < channels; j++) {
          IOMisc.readFully(stream, buf);
          waveform[j][i] = fbuf.get(0);
        }      
    } else {
      throw new IllegalArgumentException(
        "Unsupported encoding and quantization bits: " +
        "encoding = " + encoding + ", bits = " + quantizationBits
      );
    }

    return waveform;
  }

  /**
   * AudioInputStreamからデータを読み込んでデコードし，
   * 全チャネルの平均波形が格納された配列を返す．
   *
   * @param stream ストリーム
   * @return 全チャネルの平均波形が格納された配列
   * @throws IOException 入出力例外が発生した場合
   */
  public static final double[] readWaveformMonaural(final AudioInputStream stream)
    throws IOException {
    final AudioFormat format = stream.getFormat();
    final double sampleRate = format.getSampleRate();

    final int samples = (int)stream.getFrameLength();
    final int channels = format.getChannels();
    final AudioFormat.Encoding encoding = format.getEncoding();
    final int quantizationBits = format.getSampleSizeInBits();
    final ByteOrder endian = format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    final double[] waveform = new double[samples];

    if (encoding == AudioFormat.Encoding.PCM_UNSIGNED && quantizationBits == 8) {
      final byte[] buf = new byte[1];
      for (int i = 0; i < samples; i++) {
        for (int j = 0; j < channels; j++) {
          IOMisc.readFully(stream, buf);
          waveform[i] = unsignedByte2Double(buf[0]);
        }
        waveform[i] /= channels;
      }
    } else if (encoding == AudioFormat.Encoding.PCM_SIGNED && quantizationBits == 16) {
      final byte[] buf = new byte[2];
      final ShortBuffer sbuf = ByteBuffer.wrap(buf).order(endian).asShortBuffer();
      for (int i = 0; i < samples; i++) {
        for (int j = 0; j < channels; j++) {
          IOMisc.readFully(stream, buf);
          waveform[i] = short2Double(sbuf.get(0));
        }
        waveform[i] /= channels;
      }
    } else if (encoding == AudioFormat.Encoding.PCM_SIGNED && quantizationBits == 24) {
      final byte[] buf = new byte[3];
      for (int i = 0; i < samples; i++) {
        for (int j = 0; j < channels; j++) {
          IOMisc.readFully(stream, buf);
          waveform[i] = signed3Bytes2Double(buf);
        }
        waveform[i] /= channels;
      }
    } else if (encoding == AudioFormat.Encoding.PCM_FLOAT && quantizationBits == 32) {
      final byte[] buf = new byte[4];
      final FloatBuffer fbuf = ByteBuffer.wrap(buf).order(endian).asFloatBuffer();
      for (int i = 0; i < samples; i++) {
        for (int j = 0; j < channels; j++) {
          IOMisc.readFully(stream, buf);
          waveform[i] = fbuf.get(0);
        }
        waveform[i] /= channels;
      }
    } else {
      throw new IllegalArgumentException(
        "Unsupported encoding and quantization bits: " +
        "encoding = " + encoding + ", bits = " + quantizationBits
      );
    }

    return waveform;
  }

  /**
   * 与えられた音響信号配列をWAVファイルに書き出す。
   * WAVファイルは16bit符号あり整数でエンコードされる。
   *
   * @param waveform 音響信号配列
   * @param sampleRate サンプリングレート
   * @param wavFile WAVファイル
   * @throws IOException 入出力例外が発生した場合
   */
  public static final void writeWav(
    final double[] waveform,
    final double sampleRate,
    final File wavFile
  ) throws IOException {
    /* 波形をバイト列に変換 */
    final byte[] buffer = new byte[waveform.length * 2];
    final ShortBuffer sbuf = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
    for (int i = 0; i < waveform.length; i++)
      sbuf.put((short)(waveform[i] * 32768.0));

    /* バイト列からストリームを作成 */
    final ByteArrayInputStream bais = new ByteArrayInputStream(buffer);
    final AudioFormat format = new AudioFormat((float)sampleRate, 16, 1, true, false);
    final AudioInputStream ais = new AudioInputStream(bais, format, waveform.length);

    /* ファイル書き出し */
    AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavFile);
  }

  public static final byte[] toByteArray(
    final double[] waveform,
    final AudioFormat format
  ) throws UnsupportedAudioFileException {
    /* 波形をバイト列に変換 */
    if (format.getEncoding().equals(AudioFormat.Encoding.PCM_UNSIGNED) &&
        format.getSampleSizeInBits() == 8) {
      /* unsigned 8bit (byte) */
      final byte[] buffer = new byte[waveform.length];
      final ByteBuffer bbuf = ByteBuffer.wrap(buffer);
      for (int i = 0; i < waveform.length; i++)
        bbuf.put((byte)((int)(waveform[i] * 0x7F) ^ 0xFFFFFF80));
      return buffer;
    } else if (format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED) &&
               format.getSampleSizeInBits() == 16) {
      /* signed 16bit (short) */
      final byte[] buffer = new byte[waveform.length * 2];
      final ShortBuffer sbuf = ByteBuffer.wrap(buffer).order(
        format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN
      ).asShortBuffer();
      for (int i = 0; i < waveform.length; i++)
        sbuf.put((short)(waveform[i] * 0x7FFF));
      return buffer;
    } else if (format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED) &&
               format.getSampleSizeInBits() == 24) {
      /* signed 24bit */
      throw new Error("UNDER CONSTRUCTION");
    } else if (format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED) &&
               format.getSampleSizeInBits() == 32) {
      /* signed 32bit (int) */
      final byte[] buffer = new byte[waveform.length * 4];
      final IntBuffer ibuf = ByteBuffer.wrap(buffer).order(
        format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN
      ).asIntBuffer();
      for (int i = 0; i < waveform.length; i++)
        ibuf.put((int)(waveform[i] * 0x7FFFFFFF));
      return buffer;
    } else if (format.getEncoding().equals(AudioFormat.Encoding.PCM_FLOAT) &&
               format.getSampleSizeInBits() == 32) {
      /* float 32bit (float) */
      final byte[] buffer = new byte[waveform.length * 4];
      final FloatBuffer fbuf = ByteBuffer.wrap(buffer).order(
        format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN
      ).asFloatBuffer();
      for (int i = 0; i < waveform.length; i++)
        fbuf.put((float)waveform[i]);
      return buffer;
    } else if (format.getEncoding().equals(AudioFormat.Encoding.PCM_FLOAT) &&
               format.getSampleSizeInBits() == 64) {
      /* float 64bit (double) */
      final byte[] buffer = new byte[waveform.length * 8];
      final DoubleBuffer dbuf = ByteBuffer.wrap(buffer).order(
        format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN
      ).asDoubleBuffer();
      dbuf.put(waveform);
      return buffer;
    }
    throw new UnsupportedAudioFileException(format.toString());
  }

  public static final byte[] toByteArray(
    final double[][] waveform,
    final AudioFormat format
  ) throws UnsupportedAudioFileException {
    /* チャネル数チェック */
    final int channels = format.getChannels();
    if (waveform.length != channels)
      throw new IllegalArgumentException(
        "waveform.length must be equal to format.channels: " +
        "waveform.length = " + waveform.length + ", " +
        "format.channels = " + channels
      );

    /* サンプル数チェック */
    final int samples = waveform[0].length;
    if (!Arrays.stream(waveform, 1, channels)
               .allMatch(w -> w.length == samples)) {
      throw new IllegalArgumentException(
        "waveform.each.length must be equal: " +
        Arrays.stream(waveform)
              .map(w -> Integer.toString(w.length))
              .collect(Collectors.joining(","))
      );
    }

    final AudioFormat.Encoding encoding = format.getEncoding();
    final int sampleSize = format.getSampleSizeInBits();
    final ByteOrder endian =
      format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;

    /* 波形をバイト列に変換 */
    if (encoding.equals(AudioFormat.Encoding.PCM_UNSIGNED) &&
        sampleSize == 8) {
      /* unsigned 8bit (byte) */
      final byte[] buffer = new byte[channels * samples * (sampleSize >> 3)];
      final ByteBuffer bbuf = ByteBuffer.wrap(buffer);
      for (int i = 0; i < samples; i++)
        for (int j = 0; j < channels; j++)
          bbuf.put((byte)((int)(waveform[j][i] * 0x7f) ^ 0xffffff80));
      return buffer;
    } else if (encoding.equals(AudioFormat.Encoding.PCM_SIGNED) &&
               sampleSize == 16) {
      /* signed 16bit (short) */
      final byte[] buffer = new byte[channels * samples * (sampleSize >> 3)];
      final ShortBuffer sbuf =
        ByteBuffer.wrap(buffer).order(endian).asShortBuffer();
      for (int i = 0; i < samples; i++)
        for (int j = 0; j < channels; j++)
          sbuf.put((short)(waveform[j][i] * 0x7fff));
      return buffer;
    } else if (encoding.equals(AudioFormat.Encoding.PCM_SIGNED) &&
               sampleSize == 24) {
      /* signed 24bit */
      throw new Error("UNDER CONSTRUCTION");
    } else if (encoding.equals(AudioFormat.Encoding.PCM_SIGNED) &&
               sampleSize == 32) {
      /* signed 32bit (int) */
      final byte[] buffer = new byte[channels * samples * (sampleSize >> 3)];
      final IntBuffer ibuf =
        ByteBuffer.wrap(buffer).order(endian).asIntBuffer();
      for (int i = 0; i < samples; i++)
        for (int j = 0; j < channels; j++)
          ibuf.put((int)(waveform[j][i] * 0x7ffffff));
      return buffer;
    } else if (encoding.equals(AudioFormat.Encoding.PCM_FLOAT) &&
               sampleSize == 32) {
      /* float 32bit (float) */
      final byte[] buffer = new byte[channels * samples * (sampleSize >> 3)];
      final FloatBuffer fbuf =
        ByteBuffer.wrap(buffer).order(endian).asFloatBuffer();
      for (int i = 0; i < samples; i++)
        for (int j = 0; j < channels; j++)
          fbuf.put((float)waveform[j][i]);
      return buffer;
    } else if (encoding.equals(AudioFormat.Encoding.PCM_FLOAT) &&
               sampleSize == 64) {
      /* float 64bit (double) */
      final byte[] buffer = new byte[channels * samples * (sampleSize >> 3)];
      final DoubleBuffer dbuf =
        ByteBuffer.wrap(buffer).order(endian).asDoubleBuffer();
      for (int i = 0; i < samples; i++)
        for (int j = 0; j < channels; j++)
          dbuf.put(waveform[j][i]);
      return buffer;
    }
    throw new UnsupportedAudioFileException(format.toString());
  }

  /**
   * 与えられた音響信号配列をWAVファイルに書き出す。
   * 書き出し可能なエンコード方式は8bit符号なし整数、16bit符号あり整数、
   * 32bit符号あり整数、32bit浮動小数点数、64bit浮動小数点数。
   *
   * @param waveform 音響信号配列
   * @param format フォーマット
   * @param wavFile WAVファイル
   * @throws UnsupportedAudioFileException 未知のエンコード方式が指定された場合
   * @throws IOException 入出力例外が発生した場合
   */
  public static final void writeWav(
    final double[] waveform,
    final AudioFormat format,
    final File wavFile
  ) throws UnsupportedAudioFileException, IOException {
    /* 波形をフォーマットに応じたバイト列に変換 */
    final byte[] buffer = toByteArray(waveform, format);

    /* バイト列からストリームを作成 */
    final AudioInputStream ais = new AudioInputStream(
      new ByteArrayInputStream(buffer),
      format,
      waveform.length
    );

    /* ファイル書き出し */
    AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavFile);
  }

  private static final double unsignedByte2Double(byte x) {
    return (x ^ 0xffffff80) / 128.0;
  }

  private static final double short2Double(short x) {
    return x / 32768.0;
  }

  private static final double signed3Bytes2Double(final byte[] x) {
    return ((x[2] << 16) | ((x[1] & 0xff) << 8) | (x[0] & 0xff)) / 8388608.0;
  }

  public static final ScheduledExecutorService
  newSingleDaemonThreadScheduledExecutor() {
    return Executors.newSingleThreadScheduledExecutor(
      new ThreadFactory() {
        public final Thread newThread(final Runnable r) {
          final Thread t = new Thread(r);
          t.setDaemon(true);
          return t;
        }
      }
    );
  }

}

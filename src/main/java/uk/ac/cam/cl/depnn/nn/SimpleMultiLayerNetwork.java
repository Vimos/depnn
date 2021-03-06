package uk.ac.cam.cl.depnn.nn;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.cpu.NDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.ops.transforms.Transforms;

import uk.ac.cam.cl.depnn.io.NNType;

public class SimpleMultiLayerNetwork<T extends NNType> {
	private int INPUT_LAYER_SIZE;
	private int HIDDEN_LAYER_SIZE;
	private int OUTPUT_LAYER_SIZE;

	private INDArray w_h;
	private INDArray w_out;
	private INDArray b_h;
	private INDArray b_out;

	private final static Logger logger = LogManager.getLogger(SimpleMultiLayerNetwork.class);

	public INDArray getMatrix(int offset, int size) {
		// return w_h.get(NDArrayIndex.point(i * size), NDArrayIndex.interval(i * size, (i+1) * size));
		// ugly, but certainly works
		INDArray res = new NDArray(size, HIDDEN_LAYER_SIZE);

		for ( int i = 0; i < size; i++ ) {
			res.putRow(i, w_h.getRow((offset * size) + i));
		}

		return res;
	}

	public SimpleMultiLayerNetwork(String coefficientsFile, int inputLayerSize, int hiddenLayerSize, int outputLayerSize) throws IOException {
		INPUT_LAYER_SIZE = inputLayerSize;
		HIDDEN_LAYER_SIZE = hiddenLayerSize;
		OUTPUT_LAYER_SIZE = outputLayerSize;

		DataInputStream dis = new DataInputStream(new FileInputStream(coefficientsFile));
		INDArray newParams = Nd4j.read(dis);

		int idx = 0;
		int range = 0;

		range = INPUT_LAYER_SIZE * HIDDEN_LAYER_SIZE;
		w_h = newParams.get(NDArrayIndex.point(0), NDArrayIndex.interval(idx, range + idx));
		w_h = w_h.reshape(HIDDEN_LAYER_SIZE, INPUT_LAYER_SIZE).transposei();
		idx += range;

		range = 1 * HIDDEN_LAYER_SIZE;
		b_h = newParams.get(NDArrayIndex.point(0), NDArrayIndex.interval(idx, range + idx));
		b_h = b_h.reshape(HIDDEN_LAYER_SIZE, 1).transposei();
		idx += range;

		range = HIDDEN_LAYER_SIZE * OUTPUT_LAYER_SIZE;
		w_out = newParams.get(NDArrayIndex.point(0), NDArrayIndex.interval(idx, range + idx));
		w_out = w_out.reshape(OUTPUT_LAYER_SIZE, HIDDEN_LAYER_SIZE).transposei();
		idx += range;

		range = 1 * OUTPUT_LAYER_SIZE;
		b_out = newParams.get(NDArrayIndex.point(0), NDArrayIndex.interval(idx, range + idx));
		b_out = b_out.reshape(OUTPUT_LAYER_SIZE, 1).transposei();
		idx += range;
	}

	private INDArray relui(INDArray inputs) {
		INDArray mask = inputs.gt(0);
		inputs.muli(mask);

		return inputs;
	}

	private INDArray softmaxi(INDArray inputs) {
		Transforms.exp(inputs, false);
		INDArray sum = inputs.sum(1);
		inputs.diviColumnVector(sum);

		return inputs;
	}

	public INDArray output(INDArray inputs, boolean training) {
		INDArray hidden_layer = Transforms.relu(inputs.mmul(w_h).addiRowVector(b_h));
		INDArray res = softmaxi(hidden_layer.mmul(w_out).addiRowVector(b_out));
		return res;
	}

	public INDArray outputPrecompute(INDArray inputs, boolean training) {
		INDArray hidden_layer = Transforms.relu(inputs.addiRowVector(b_h));
		INDArray res = softmaxi(hidden_layer.mmul(w_out).addiRowVector(b_out));
		return res;
	}
}
package uk.ac.cam.cl.depnn;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.canova.api.util.ClassPathResource;
import org.canova.api.writable.Writable;
import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.eval.Evaluation;
import org.deeplearning4j.models.embeddings.loader.WordVectorSerializer;
import org.deeplearning4j.models.word2vec.Word2Vec;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.text.sentenceiterator.BasicLineIterator;
import org.deeplearning4j.text.sentenceiterator.SentenceIterator;
import org.deeplearning4j.text.tokenization.tokenizer.preprocessor.CommonPreprocessor;
import org.deeplearning4j.text.tokenization.tokenizerfactory.DefaultTokenizerFactory;
import org.deeplearning4j.text.tokenization.tokenizerfactory.TokenizerFactory;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import uk.ac.cam.cl.depnn.embeddings.Embeddings;
import uk.ac.cam.cl.depnn.utils.ModelUtils;

public class DependencyNeuralNetwork {
	private int W2V_MIN_WORD_FREQUENCY;
	private int W2V_ITERATIONS;
	private int W2V_LAYER_SIZE;
	private int W2V_SEED;
	private int W2V_WINDOW_SIZE;

	private int NN_NUM_PROPERTIES;
	private int NN_BATCH_SIZE;
	private int NN_ITERATIONS;
	private int NN_HIDDEN_LAYER_SIZE;
	private int NN_SEED;
	private double NN_LEARNING_RATE;
	private double NN_L1_REG;
	private double NN_L2_REG;
	private double NN_DROPOUT;
	private double NN_EMBED_RANDOM_RANGE;

	private Embeddings catEmbeddings;
	private Embeddings slotEmbeddings;
	private Embeddings distEmbeddings;
	private Embeddings posEmbeddings;

	private Word2Vec word2vec;
	private MultiLayerNetwork network;

	private final static Logger logger = LogManager.getLogger(DependencyNeuralNetwork.class);

	public Word2Vec getWord2Vec() {
		return word2vec;
	}

	public MultiLayerNetwork getNetwork() {
		return network;
	}

	// training
	public DependencyNeuralNetwork(int w2vMinWordFreq,
	                               int w2vIterations,
	                               int w2vLayerSize,
	                               int w2vSeed,
	                               int w2vWindowSize,
	                               int nnNumProperties,
	                               int nnBatchSize,
	                               int nnIterations,
	                               int nnHiddenLayerSize,
	                               int nnSeed,
	                               double nnLearningRate,
	                               double nnL1Reg,
	                               double nnL2Reg,
	                               double nnDropout,
	                               double nnEmbedRandomRange) {
		W2V_MIN_WORD_FREQUENCY = w2vMinWordFreq;
		W2V_ITERATIONS = w2vIterations;
		W2V_LAYER_SIZE = w2vLayerSize;
		W2V_SEED = w2vSeed;
		W2V_WINDOW_SIZE = w2vWindowSize;

		NN_NUM_PROPERTIES = nnNumProperties;
		NN_BATCH_SIZE = nnBatchSize;
		NN_ITERATIONS = nnIterations;
		NN_HIDDEN_LAYER_SIZE = nnHiddenLayerSize;
		NN_SEED = nnSeed;
		NN_LEARNING_RATE = nnLearningRate;
		NN_L1_REG = nnL1Reg;
		NN_L2_REG = nnL2Reg;
		NN_DROPOUT = nnDropout;
		NN_EMBED_RANDOM_RANGE = nnEmbedRandomRange;
	}

	// running
	public DependencyNeuralNetwork(String modelFile,
	                               String configJsonFile,
	                               String coefficientsFile,
	                               String catEmbeddingsFile,
	                               String slotEmbeddingsFile,
	                               String distEmbeddingsFile,
	                               String posEmbeddingsFile) throws IOException {
		word2vec = WordVectorSerializer.loadFullModel(modelFile);
		network = ModelUtils.loadModelAndParameters(new File(configJsonFile), coefficientsFile);

		catEmbeddings = new Embeddings(catEmbeddingsFile);
		slotEmbeddings = new Embeddings(slotEmbeddingsFile);
		distEmbeddings = new Embeddings(distEmbeddingsFile);
		posEmbeddings = new Embeddings(posEmbeddingsFile);
	}

	public void trainWord2Vec(String sentencesFile) throws FileNotFoundException {
		String filePath = new ClassPathResource(sentencesFile).getFile().getAbsolutePath();

		SentenceIterator iter = new BasicLineIterator(filePath);
		TokenizerFactory t = new DefaultTokenizerFactory();
		t.setTokenPreProcessor(new CommonPreprocessor());

		word2vec = new Word2Vec.Builder()
				.minWordFrequency(W2V_MIN_WORD_FREQUENCY)
				.iterations(W2V_ITERATIONS)
				.layerSize(W2V_LAYER_SIZE)
				.seed(W2V_SEED)
				.windowSize(W2V_WINDOW_SIZE)
				.iterate(iter)
				.tokenizerFactory(t)
				.build();

		word2vec.fit();
	}

	public void serializeWord2Vec(String modelFile) {
		WordVectorSerializer.writeFullModel(word2vec, modelFile);
	}

	public void trainNetwork(String dependenciesDir) throws IOException, InterruptedException {
		int numInput = W2V_LAYER_SIZE * NN_NUM_PROPERTIES;
		int numOutput = 2;

		Nd4j.MAX_SLICES_TO_PRINT = -1;
		Nd4j.MAX_ELEMENTS_PER_SLICE = -1;
		Nd4j.ENFORCE_NUMERICAL_STABILITY = true;

		DependencyDataSetIterator iter = new DependencyDataSetIterator(this, dependenciesDir, NN_BATCH_SIZE, W2V_LAYER_SIZE, NN_NUM_PROPERTIES);

		catEmbeddings = new Embeddings(iter.getCatLexicon(), W2V_LAYER_SIZE, NN_EMBED_RANDOM_RANGE);
		slotEmbeddings = new Embeddings(iter.getSlotLexicon(), W2V_LAYER_SIZE, NN_EMBED_RANDOM_RANGE);
		distEmbeddings = new Embeddings(iter.getDistLexicon(), W2V_LAYER_SIZE, NN_EMBED_RANDOM_RANGE);
		posEmbeddings = new Embeddings(iter.getPosLexicon(), W2V_LAYER_SIZE, NN_EMBED_RANDOM_RANGE);

		MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
				.seed(NN_SEED)
				.iterations(NN_ITERATIONS)
				.learningRate(NN_LEARNING_RATE)
				.optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
				.regularization(true)
				.l1(NN_L1_REG)
				.l2(NN_L2_REG)
				.useDropConnect(true)
				.list()
				.layer(0, new DenseLayer.Builder()
								.nIn(numInput)
								.nOut(NN_HIDDEN_LAYER_SIZE)
								.activation("relu")
								.weightInit(WeightInit.XAVIER)
								.updater(Updater.ADAGRAD)
								.dropOut(NN_DROPOUT)
								.build()
				)
				.layer(1, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
								.nIn(NN_HIDDEN_LAYER_SIZE)
								.nOut(numOutput)
								.activation("softmax")
								.weightInit(WeightInit.XAVIER)
								.build()
				)
				.pretrain(false)
				.backprop(true)
				.build();

		network = new MultiLayerNetwork(conf);
		network.init();

		while ( iter.hasNext() ) {
			Pair<DataSet, List<ArrayList<Writable>>> next = iter.next();
			DataSet trainBatch = next.getFirst();
			List<ArrayList<Writable>> trainList = next.getSecond();

			trainBatch.normalizeZeroMeanZeroUnitVariance();
			network.fit(trainBatch);

			INDArray epsilon = network.epsilon();

			for ( int i = 0; i < epsilon.rows(); i++ ) {
				INDArray errors = epsilon.getRow(i);
				ArrayList<Writable> record = trainList.get(i);

				catEmbeddings.addEmbedding(record.get(1).toString(), errors, 1 * W2V_LAYER_SIZE);
				slotEmbeddings.addEmbedding(record.get(2).toString(), errors, 2 * W2V_LAYER_SIZE);
				distEmbeddings.addEmbedding(record.get(4).toString(), errors, 4 * W2V_LAYER_SIZE);
				posEmbeddings.addEmbedding(record.get(5).toString(), errors, 5 * W2V_LAYER_SIZE);
				posEmbeddings.addEmbedding(record.get(6).toString(), errors , 6 * W2V_LAYER_SIZE);
			}
		}
	}

	public void testNetwork(String testDir) throws IOException, InterruptedException {
		Evaluation eval = new Evaluation();

		DependencyDataSetIterator iter = new DependencyDataSetIterator(this, testDir, 0, W2V_LAYER_SIZE, NN_NUM_PROPERTIES);

		DataSet test = iter.next().getFirst();

		INDArray predictions = network.output(test.getFeatureMatrix());
		eval.eval(test.getLabels(), predictions);

		logger.info(eval.stats());
	}

	public void serializeNetwork(String configJsonFile, String coefficientsFile) throws IOException {
		ModelUtils.saveModelAndParameters(network, new File(configJsonFile), coefficientsFile);
	}

	public int predict(String head,
                       String category,
                       String slot,
                       String dependent,
                       String distance,
                       String headPos,
                       String dependentPos) {
		return network.predict(makeVector(head,
									category,
									slot,
									dependent,
									distance,
									headPos,
									dependentPos))[0];
	}

	protected INDArray makeVector(String head,
	                            String category,
	                            String slot,
	                            String dependent,
	                            String distance,
	                            String headPos,
	                            String dependentPos) {
		INDArray headVector = word2vec.getWordVectorMatrix(head);
		INDArray dependentVector = word2vec.getWordVectorMatrix(dependent);

		INDArray categoryVector = catEmbeddings.getINDArray(category);
		INDArray slotVector = slotEmbeddings.getINDArray(slot);
		INDArray distanceVector = distEmbeddings.getINDArray(distance);
		INDArray headPosVector = posEmbeddings.getINDArray(headPos);
		INDArray dependentPosVector= posEmbeddings.getINDArray(dependentPos);

		return Nd4j.concat(1, headVector,
							categoryVector,
							slotVector,
							dependentVector,
							distanceVector,
							headPosVector,
							dependentPosVector);
	}

	public void serializeEmbeddings(String catEmbeddingsFile,
	                                String slotEmbeddingsFile,
	                                String distEmbeddingsFile,
	                                String posEmbeddingsFile) throws IOException {
		catEmbeddings.serializeEmbeddings(catEmbeddingsFile);
		slotEmbeddings.serializeEmbeddings(slotEmbeddingsFile);
		distEmbeddings.serializeEmbeddings(distEmbeddingsFile);
		posEmbeddings.serializeEmbeddings(posEmbeddingsFile);
	}
}

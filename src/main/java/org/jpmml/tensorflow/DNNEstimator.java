/*
 * Copyright (c) 2017 Villu Ruusmann
 *
 * This file is part of JPMML-TensorFlow
 *
 * JPMML-TensorFlow is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-TensorFlow is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-TensorFlow.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpmml.tensorflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import com.google.common.primitives.Floats;
import org.dmg.pmml.DerivedField;
import org.dmg.pmml.Entity;
import org.dmg.pmml.NormDiscrete;
import org.dmg.pmml.OpType;
import org.dmg.pmml.neural_network.Connection;
import org.dmg.pmml.neural_network.NeuralInput;
import org.dmg.pmml.neural_network.NeuralInputs;
import org.dmg.pmml.neural_network.NeuralLayer;
import org.dmg.pmml.neural_network.NeuralNetwork;
import org.dmg.pmml.neural_network.Neuron;
import org.jpmml.converter.BinaryFeature;
import org.jpmml.converter.CMatrixUtil;
import org.jpmml.converter.Feature;
import org.tensorflow.Operation;
import org.tensorflow.Output;
import org.tensorflow.Tensor;
import org.tensorflow.framework.NodeDef;

abstract
public class DNNEstimator extends Estimator {

	public DNNEstimator(SavedModel savedModel, String head){
		super(savedModel, head);
	}

	protected NeuralNetwork encodeNeuralNetwork(TensorFlowEncoder encoder){
		SavedModel savedModel = getSavedModel();

		NeuralNetwork neuralNetwork = new NeuralNetwork()
			.setActivationFunction(NeuralNetwork.ActivationFunction.RECTIFIER);

		List<NodeDef> biasAdds = Lists.newArrayList(savedModel.getInputs(getHead(), "BiasAdd"));

		biasAdds = Lists.reverse(biasAdds);

		List<? extends Entity> entities;

		{
			NodeDef biasAdd = biasAdds.get(0);

			NodeDef matMul = savedModel.getNodeDef(biasAdd.getInput(0));
			if(!("MatMul").equals(matMul.getOp())){
				throw new IllegalArgumentException();
			}

			NodeDef concat = savedModel.getNodeDef(matMul.getInput(0));
			if(!("ConcatV2").equals(concat.getOp())){
				throw new IllegalArgumentException();
			}

			int id = 0;

			NeuralInputs neuralInputs = new NeuralInputs();

			List<String> inputNames = concat.getInputList();
			for(int i = 0; i < inputNames.size() - 1; i++){
				String inputName = inputNames.get(i);

				NodeDef term = savedModel.getNodeDef(inputName);

				// "real_valued_column"
				if(("Cast").equals(term.getOp()) || ("Placeholder").equals(term.getOp())){
					NodeDef placeholder = term;

					Feature feature = encoder.createContinuousFeature(savedModel, placeholder);

					DerivedField derivedField = new DerivedField(OpType.CONTINUOUS, feature.getDataType())
						.setExpression(feature.ref());

					NeuralInput neuralInput = new NeuralInput(String.valueOf(id), derivedField);

					neuralInputs.addNeuralInputs(neuralInput);

					id++;
				} else

				// "one_hot_column(sparse_column_with_keys)"
				if(("Sum").equals(term.getOp())){
					NodeDef oneHot = savedModel.getOnlyInput(term.getInput(0), "OneHot");

					NodeDef placeholder = savedModel.getOnlyInput(oneHot.getInput(0), "Placeholder");
					NodeDef findTable = savedModel.getOnlyInput(oneHot.getInput(0), "LookupTableFind");

					Map<?, ?> table = savedModel.getTable(findTable.getInput(0));

					List<String> categories = (List)new ArrayList<>(table.keySet());

					List<BinaryFeature> binaryFeatures = encoder.createBinaryFeatures(savedModel, placeholder, categories);
					for(BinaryFeature binaryFeature : binaryFeatures){
						NormDiscrete normDiscrete = new NormDiscrete(binaryFeature.getName(), binaryFeature.getValue());

						DerivedField derivedField = new DerivedField(OpType.CONTINUOUS, binaryFeature.getDataType())
							.setExpression(normDiscrete);

						NeuralInput neuralInput = new NeuralInput(String.valueOf(id), derivedField);

						neuralInputs.addNeuralInputs(neuralInput);

						id++;
					}
				} else

				{
					throw new IllegalArgumentException(term.getName());
				}
			}

			neuralNetwork.setNeuralInputs(neuralInputs);

			entities = neuralInputs.getNeuralInputs();
		}

		for(int i = 0; i < biasAdds.size(); i++){
			NodeDef biasAdd = biasAdds.get(i);

			NodeDef matMul = savedModel.getNodeDef(biasAdd.getInput(0));
			if(!("MatMul").equals(matMul.getOp())){
				throw new IllegalArgumentException();
			}

			int count;

			{
				Operation operation = savedModel.getOperation(matMul.getName());

				Output output = operation.output(0);

				long[] shape = ShapeUtil.toArray(output.shape());
				if(shape.length != 2 || shape[0] != -1){
					throw new IllegalArgumentException();
				}

				count = (int)shape[1];
			}

			NodeDef weights = savedModel.getOnlyInput(matMul.getInput(1), "VariableV2");

			float[] weightValues;

			try(Tensor tensor = savedModel.run(weights.getName())){
				weightValues = TensorUtil.toFloatArray(tensor);
			}

			NodeDef bias = savedModel.getOnlyInput(biasAdd.getInput(1), "VariableV2");

			float[] biasValues;

			try(Tensor tensor = savedModel.run(bias.getName())){
				biasValues = TensorUtil.toFloatArray(tensor);
			}

			NeuralLayer neuralLayer = new NeuralLayer();

			for(int j = 0; j < count; j++){
				Neuron neuron = new Neuron(String.valueOf(i) + "/" + String.valueOf(j), null)
					.setBias(floatToDouble(biasValues[j]));

				List<Float> entityWeights = CMatrixUtil.getColumn(Floats.asList(weightValues), entities.size(), count, j);

				for(int k = 0; k < entities.size(); k++){
					Entity entity = entities.get(k);
					Float entityWeight = entityWeights.get(k);

					Connection connection = new Connection(entity.getId(), floatToDouble(entityWeight));

					neuron.addConnections(connection);
				}

				neuralLayer.addNeurons(neuron);
			}

			neuralNetwork.addNeuralLayers(neuralLayer);

			entities = neuralLayer.getNeurons();
		}

		return neuralNetwork;
	}
}
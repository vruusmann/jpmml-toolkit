/*
 * Copyright (c) 2016 Villu Ruusmann
 *
 * This file is part of JPMML-Toolkit
 *
 * JPMML-Toolkit is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-Toolkit is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-Toolkit.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpmml.toolkit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.xml.transform.Source;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.collect.Iterables;
import org.dmg.pmml.DataDictionary;
import org.dmg.pmml.DataField;
import org.dmg.pmml.DefineFunction;
import org.dmg.pmml.DerivedField;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.FieldUsageType;
import org.dmg.pmml.Header;
import org.dmg.pmml.Indexable;
import org.dmg.pmml.Interval;
import org.dmg.pmml.MiningField;
import org.dmg.pmml.MiningFunctionType;
import org.dmg.pmml.MiningModel;
import org.dmg.pmml.MiningSchema;
import org.dmg.pmml.Model;
import org.dmg.pmml.MultipleModelMethodType;
import org.dmg.pmml.Output;
import org.dmg.pmml.PMML;
import org.dmg.pmml.Segmentation;
import org.dmg.pmml.Targets;
import org.dmg.pmml.TransformationDictionary;
import org.dmg.pmml.Value;
import org.dmg.pmml.Visitor;
import org.jpmml.converter.MiningModelUtil;
import org.jpmml.converter.ModelUtil;
import org.jpmml.converter.PMMLUtil;
import org.jpmml.model.ImportFilter;
import org.jpmml.model.JAXBUtil;
import org.jpmml.model.MetroJAXBUtil;
import org.jpmml.model.visitors.DictionaryCleaner;
import org.jpmml.model.visitors.MiningSchemaCleaner;
import org.xml.sax.InputSource;

public class Aggregator {

	@Parameter (
		names = {"--input"},
		description = "Input PMML file list",
		required = true
	)
	private List<File> inputFiles = null;

	@Parameter (
		names = {"--weights"},
		description = "Input weights"
	)
	private List<Double> inputWeights = null;

	@Parameter (
		names = {"--output"},
		description = "Output PMML file",
		required = true
	)
	private File outputFile = null;


	static
	public void main(String... args) throws Exception {
		Aggregator aggregator = new Aggregator();

		JCommander commander = new JCommander(aggregator);
		commander.setProgramName(Aggregator.class.getName());

		try {
			commander.parse(args);
		} catch(ParameterException pe){
			throw pe;
		}

		aggregator.run();
	}

	private void run() throws Exception {

		if(this.inputWeights != null && (this.inputFiles.size() != this.inputWeights.size())){
			throw new IllegalArgumentException();
		}

		Map<FieldName, DataField> dataFields = new LinkedHashMap<>();

		Map<FieldName, DerivedField> derivedFields = new LinkedHashMap<>();
		Map<String, DefineFunction> defineFunctions = new LinkedHashMap<>();

		List<Model> models = new ArrayList<>();

		MiningFunctionType miningFunction = null;

		FieldName targetField = null;

		List<File> inputFiles = this.inputFiles;
		for(File inputFile : inputFiles){
			PMML pmml = unmarshal(inputFile);

			DataDictionary dataDictionary = pmml.getDataDictionary();
			if(dataDictionary != null){
				merge(dataFields, dataDictionary.getDataFields());
			}

			TransformationDictionary transformationDictionary = pmml.getTransformationDictionary();
			if(transformationDictionary != null){
				append(derivedFields, transformationDictionary.getDerivedFields());
				append(defineFunctions, transformationDictionary.getDefineFunctions());
			}

			Model model = Iterables.getOnlyElement(pmml.getModels());

			MiningFunctionType modelMiningFunction = model.getFunctionName();
			if(miningFunction == null){
				miningFunction = modelMiningFunction;
			} else

			{
				if(!(miningFunction).equals(modelMiningFunction)){
					throw new IllegalArgumentException();
				}
			}

			Targets targets = model.getTargets();
			if(targets != null){
				throw new IllegalArgumentException();
			}

			FieldName modelTargetField = extractTargetField(model);
			if(targetField == null){
				targetField = modelTargetField;
			} else

			{
				if(!(targetField).equals(modelTargetField)){
					throw new IllegalArgumentException();
				}
			}

			Output output = model.getOutput();
			if(output != null){
				model.setOutput(null);
			}

			models.add(model);
		}

		DataField dataField = dataFields.get(targetField);
		if(dataField == null){
			throw new IllegalArgumentException();
		}

		Segmentation segmentation;

		if(this.inputWeights != null){
			segmentation = MiningModelUtil.createSegmentation(MultipleModelMethodType.WEIGHTED_AVERAGE, models, (List)this.inputWeights);
		} else

		{
			segmentation = MiningModelUtil.createSegmentation(MultipleModelMethodType.AVERAGE, models);
		}

		Output output = null;

		switch(miningFunction){
			case CLASSIFICATION:
				output = new Output(ModelUtil.createProbabilityFields(dataField));
				break;
			default:
				break;
		}

		MiningSchema miningSchema = ModelUtil.createMiningSchema(targetField, Collections.<FieldName>emptyList());

		MiningModel miningModel = new MiningModel(miningFunction, miningSchema)
			.setSegmentation(segmentation)
			.setOutput(output);

		Header header = PMMLUtil.createHeader("JPMML-Toolkit", "1.0-SNAPSHOT");

		DataDictionary dataDictionary = new DataDictionary(new ArrayList<>(dataFields.values()));

		PMML pmml = new PMML("4.2", header, dataDictionary)
			.addModels(miningModel);

		if(derivedFields.size() > 0 || defineFunctions.size() > 0){
			TransformationDictionary transformationDictionary = new TransformationDictionary();

			if(derivedFields.size() > 0){
				(transformationDictionary.getDerivedFields()).addAll(derivedFields.values());
			} // End if

			if(defineFunctions.size() > 0){
				(transformationDictionary.getDefineFunctions()).addAll(defineFunctions.values());
			}

			pmml.setTransformationDictionary(transformationDictionary);
		}

		List<Visitor> visitors = Arrays.<Visitor>asList(new MiningSchemaCleaner(), new DictionaryCleaner());
		for(Visitor visitor : visitors){
			visitor.applyTo(pmml);
		}

		marshal(pmml, this.outputFile);
	}

	static
	private FieldName extractTargetField(Model model){
		FieldName targetField = null;

		MiningSchema miningSchema = model.getMiningSchema();

		List<MiningField> miningFields = miningSchema.getMiningFields();
		for(Iterator<MiningField> it = miningFields.iterator(); it.hasNext(); ){
			MiningField miningField = it.next();

			FieldUsageType fieldUsage = miningField.getUsageType();
			switch(fieldUsage){
				case PREDICTED:
				case TARGET:
					{
						if(targetField != null){
							throw new IllegalArgumentException();
						}

						targetField = miningField.getName();

						it.remove();
					}
					break;
				default:
					break;
			}
		}

		return targetField;
	}

	static
	private void merge(Map<FieldName, DataField> map, Collection<DataField> dataFields){

		for(DataField dataField : dataFields){
			FieldName name = dataField.getName();

			if(map.containsKey(name)){
				dataField = mergeDataField(map.get(name), dataField);
			}

			map.put(name, dataField);
		}
	}

	static
	private DataField mergeDataField(DataField left, DataField right){
		DataField result = new DataField()
			.setName(checkEqual(left.getName(), right.getName()))
			.setDataType(checkEqual(left.getDataType(), right.getDataType()))
			.setOpType(checkEqual(left.getOpType(), right.getOpType()));

		(result.getValues()).addAll(mergeValues(left.getValues(), right.getValues()));
		(result.getIntervals()).addAll(mergeIntervals(left.getIntervals(), right.getIntervals()));

		return result;
	}

	static
	private List<Value> mergeValues(List<Value> left, List<Value> right){

		if(left.isEmpty() && right.size() > 0){
			return right;
		}

		return left;
	}

	static
	private List<Interval> mergeIntervals(List<Interval> left, List<Interval> right){

		if(left.isEmpty() && right.size() > 0){
			return right;
		}

		return left;
	}

	static
	private <E extends Indexable<K>, K> void append(Map<K, E> map, Collection<E> elements){

		for(E element : elements){
			K key = element.getKey();

			if(map.containsKey(key)){
				throw new IllegalArgumentException(String.valueOf(key));
			}

			map.put(key, element);
		}
	}

	static
	private <E> E checkEqual(E left, E right){

		if(!Objects.equals(left, right)){
			throw new IllegalArgumentException(left + " != " + right);
		}

		return left;
	}

	static
	private PMML unmarshal(File file) throws Exception {

		try(InputStream is = new FileInputStream(file)){
			Source source = ImportFilter.apply(new InputSource(is));

			return JAXBUtil.unmarshalPMML(source);
		}
	}

	static
	private void marshal(PMML pmml, File file) throws Exception {

		try(OutputStream os = new FileOutputStream(file)){
			MetroJAXBUtil.marshalPMML(pmml, os);
		}
	}
}
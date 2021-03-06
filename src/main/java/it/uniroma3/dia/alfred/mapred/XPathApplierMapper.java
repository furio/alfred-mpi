package it.uniroma3.dia.alfred.mapred;

import it.uniroma3.dia.alfred.mpi.model.XPathHolder;
import it.uniroma3.dia.alfred.mpi.model.serializer.XPathHolderSerializable;

import java.io.IOException;
import java.util.Map;

import model.MaterializedRuleSet;
import model.Page;
import model.Rule;

import org.apache.commons.io.IOUtils;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.util.StringUtils;

import rules.xpath.XPathRule;

import com.atlantbh.hadoop.s3.io.S3ObjectSummaryWritable;
import com.atlantbh.hadoop.s3.io.S3ObjectWritable;
import com.google.common.collect.Maps;

public class XPathApplierMapper 
extends Mapper<S3ObjectSummaryWritable,S3ObjectWritable,Text,MapWritable>{

	private Map<String, MaterializedRuleSet> rules;	
	private int contaPagine;		
	public enum Counters { PAGE, RULE_NULL };

	@Override
	protected void setup(Context context) {

		//Carico le regole dalla DistributedCache
		Path[] cacheFiles = new Path[0];

		try {
			cacheFiles = DistributedCache.getLocalCacheFiles(context.getConfiguration());
		} catch (IOException ioe) {
			System.err.println("Caught exception while getting cached files: " + StringUtils.stringifyException(ioe));
		}

		String cacheFile = cacheFiles[0].toString();
		
		loadRules(cacheFile + "/" + context.getConfiguration().get(XPathApplierDriver.S3_XPATHRULES_FILENAME));
		this.contaPagine = 0;		
	}

	@Override
	protected void cleanup(Context context) {
		context.getCounter(Counters.PAGE).setValue(this.contaPagine);	
	}

	public void loadRules(String cachePath) {
		XPathHolder holder = XPathHolderSerializable.fromJsonFile(cachePath);

		this.rules = Maps.newHashMap();
		for (String attribute : holder.getAttributesList()) {
			MaterializedRuleSet mrToPut = new MaterializedRuleSet();
			
			for (String rule : holder.getXpathsFromAttribute(attribute)) {
				mrToPut.addRule(new XPathRule(rule));
			}

			this.rules.put(attribute, mrToPut);
		}
	}

	@Override
	public void map(S3ObjectSummaryWritable key, S3ObjectWritable value, Context context) throws IOException, InterruptedException {

		Map<String, Map<Rule, String>> result = Maps.newHashMap();
 
		String pathPage = value.getKey();
		String pageContent = IOUtils.toString(value.getObjectContent());
		
		Page p = new Page(pageContent, pathPage);

		for(String attribute: this.rules.keySet()) {
			Map<Rule, String> tempResult = Maps.newHashMap();
			
			for (Rule rule : this.rules.get(attribute).getAllRules()) {
				String estratto = rule.applyOn(p).getTextContent();
				tempResult.put(rule, estratto);

				if (estratto.equals("")) {
					context.getCounter(Counters.RULE_NULL).increment(1);
				}
			}
			
			result.put(attribute, tempResult);
		}

		MapWritable mappaRisultati = new MapWritable();

		for(String attribute: result.keySet()) {
			MapWritable mappaInside = new MapWritable();
			
			for (Rule regola : result.get(attribute).keySet()) {
				String valore = result.get(attribute).get(regola);
				if (valore != null) {
					mappaInside.put(new Text(regola.toString()), new Text(valore));
				} else {
					mappaInside.put(new Text(regola.toString()), new Text(""));
				}
			}
			
			mappaRisultati.put(new Text(attribute), mappaInside);
		}

		//Output: (path_pagina, mappa dei ris. delle regole)
		context.write(new Text(pathPage), mappaRisultati);
		this.contaPagine++;
	}
}
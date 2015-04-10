package org.aksw.cubeqa.index;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j;
import org.aksw.cubeqa.property.ComponentProperty;
import org.apache.log4j.Level;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;

/** Lucene index for labels, used by ObjectPropertyScorer.
 */
@Log4j
public class LabelIndex extends Index
{
	static {log.setLevel(Level.ALL);}
	private static final Map<String,LabelIndex> instances = new HashMap<>();
	private LabelIndex(ComponentProperty property) {super(property);}

	public static synchronized LabelIndex getInstance(ComponentProperty property)
	{
		LabelIndex index = instances.get(property.uri);
		if(index==null)
		{
			index = new LabelIndex(property);
			instances.put(property.uri,index);
		}
		return index;
	}

	@SneakyThrows
	public void fill(Set<String> uris, Function<String,Set<String>> labelFunction)
	{
		if(!DirectoryReader.indexExists(dir))
		{
			startWrites();
			for(String uri: uris)
			{
								Set<String> labels = labelFunction.apply(uri);
				add(uri, labels);
			}
			stopWrites();
		}
		reader = DirectoryReader.open(dir);
	}

	@SneakyThrows
	public Map<String,Double> getUrisWithScore(String label)
	{
		Map<String,Double> urisWithScore = new HashMap<>();
		String ns = normalize(label);
		if(ns.isEmpty()) {return urisWithScore;}
		//		PhraseQuery q = new PhraseQuery();
		//		q.add(new Term("label",label));

		//		Query q = new QueryParser("label", analyzer).parse(querystr);

		List<Query> queries;
		if(ns.length()>=FUZZY_MIN_LENGTH)
		{
			queries = Arrays.asList(new FuzzyQuery(new Term("stringlabel",ns)),parser.parse(ns));
		} else
		{
			queries = Collections.singletonList(parser.parse(ns));
		}

		int hitsPerPage = 10;
		IndexSearcher searcher = new IndexSearcher(reader);

		for(Query q: queries)
		{
			log.trace("lucene query "+q);
			TopScoreDocCollector collector = TopScoreDocCollector.create(hitsPerPage);
			searcher.search(q, collector);
			ScoreDoc[] hits = collector.topDocs().scoreDocs;

			for(ScoreDoc hit: hits)
			{
				Document doc = searcher.doc(hit.doc);

				log.trace("label index lookup on property "+property+" "+Arrays.toString(doc.getValues("originallabel")));
				// Lucene returns document retrieval score but similarity distance is better
				double score = Arrays.stream(doc.getValues("originallabel")).mapToDouble(l->(distance.getDistance(ns, normalize(l)))).max().getAsDouble();
				urisWithScore.put(doc.get("uri"),score);
			}
		}
		return urisWithScore;
	}

	public void add(String uri, Set<String> labels) throws IOException
	{
		if(indexWriter==null) throw new IllegalStateException("indexWriter is null, call startWrites() first.");
		Document doc = new Document();
		doc.add(new StringField("uri", uri, Field.Store.YES));
		//		doc.add(new TextField("cube", cube.name, Field.Store.YES));
		//		doc.add(new TextField("property", cube.name, Field.Store.YES));

		labels.forEach(l->
		{
			doc.add(new Field("stringlabel", normalize(l), StringField.TYPE_STORED));
			doc.add(new Field("textlabel", normalize(l), TextField.TYPE_STORED));
			doc.add(new Field("originallabel", l, StringField.TYPE_STORED));
		});

		indexWriter.addDocument(doc);
	}

}
package us.kbase.typedobj.core;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import us.kbase.common.utils.JsonTreeGenerator;
import us.kbase.typedobj.exceptions.ExceededMaxMetadataSizeException;
import us.kbase.typedobj.exceptions.TypedObjectExtractionException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Extraction of ws-searchable subset and selected metadata based on a json token stream.
 * 
 * @author msneddon
 * @author rsutormin
 */
public class SubsetAndMetadataExtractor {
	private static ObjectMapper mapper = new ObjectMapper();
	
	/**
	 * extract the fields listed in selection from the element and add them to the subset
	 * 
	 * Subset selection must either be an object containing structure field names to extract, '*' in the case of
	 * extracting a mapping, or '[*]' for extracting a list.  if the selection is empty, nothing is added.
	 * If extractKeysOf is set, and the element is an Object (ie a kidl mapping), then an array of the keys
	 * is added instead of the entire mapping.
	 * 
	 * we assume here that selection has already been validated against the structure of the document, so that
	 * if we get true on extractKeysOf, it really is a mapping, and if we get a '*' or '[*]', it really is
	 * a mapping or array.
	 * 
	 * Metadata extraction happens by creating a metadataExtractionHandler which has registered the metadata
	 * selections.  As metadata items are found during traversal for subset extraction, they are added to
	 * the metadata extraction handler.  Then, after calling extract fields, you can use the metadata extraction
	 * handler to get the metadata found.
	 * @throws ExceededMaxMetadataSizeException 
	 * @throws IOException 
	 * 
	 */
	public static ExtractedSubsetAndMetadata extractFields(
			TokenSequenceProvider jts, 
			ObjectNode keysOfSelection,
			ObjectNode fieldsSelection,
			long maxSubdataSize,
			long maxMetadataSize,
			MetadataExtractionHandler metadataExtractionHandler) 
					throws ExceededMaxMetadataSizeException, IOException {

		//System.out.println(keysOfSelection);
		//System.out.println(fieldsSelection);
		//System.out.println(metadataExtractionHandler);

		SubsetAndMetadataNode root = new SubsetAndMetadataNode();
		
		//if the selection is empty, we return without adding anything
		if (keysOfSelection != null && keysOfSelection.size() > 0) 
			prepareWsSubsetTree(keysOfSelection, true, root);
		if (fieldsSelection != null && fieldsSelection.size() > 0)
			prepareWsSubsetTree(fieldsSelection, false, root);
		if (metadataExtractionHandler != null) {
			metadataExtractionHandler.setMaxMetadataSize(maxMetadataSize);
			prepareMetadataSelectionTree(metadataExtractionHandler, root);
		}
		//root.printTree("  ");
		
		// if there is nothing to extract as subdata, then we create an empty node because the
		// extractFieldsWithOpenToken method will not add anything to the stream unless something
		// needs to be extracted as subdata
		if ( !root.isNeedSubsetInChildren() ) {
			if(root.getNeedValueForMetadata().isEmpty() && root.getNeedLengthForMetadata().isEmpty() && !(root.hasChildren())) {
				// no subset, no metadata, no children.  Tree is empty, so we just return an empty extraction
				return new ExtractedSubsetAndMetadata(null,null);
			} else {
				// no subset, but we need metadata so run the extraction, but without the json tree generator
				JsonToken t = jts.nextToken();
				try {
					extractFieldsWithOpenToken(jts, t, root, metadataExtractionHandler, null, new ArrayList<String>());
				} catch (TypedObjectExtractionException e) {
					throw new RuntimeException("This is bad. There is an unexpected internal error when extracting object metadata",e);
				}
				return new ExtractedSubsetAndMetadata(null,metadataExtractionHandler.getSavedMetadata());
			}
		}

		// we need subdata, so run the full method
		JsonTreeGenerator jgen = new JsonTreeGenerator(mapper);
		jgen.setMaxDataSize(maxSubdataSize);
		JsonToken t = jts.nextToken();
		try {
			extractFieldsWithOpenToken(jts, t, root, metadataExtractionHandler, jgen, new ArrayList<String>());
		} catch (TypedObjectExtractionException e) {
			throw new RuntimeException("This is bad. There is an unexpected internal error when extracting object subset or metadata",e);
		} 
		jgen.close();
		return new ExtractedSubsetAndMetadata(jgen.getTree(),metadataExtractionHandler.getSavedMetadata());
	}

	/**
	 * Method prepares parsing tree for set of key or field selections. The idea is to join two trees
	 * for keys and for fields into common tree cause we have no chance to process json tokens of
	 * real data twice.
	 */
	private static void prepareWsSubsetTree(JsonNode selection, boolean keysOf, 
			SubsetAndMetadataNode parent) {
		if (selection.size() == 0) {
			if (keysOf) {
				parent.setNeedKeys(true);
			} else {
				parent.setNeedAll(true);
			}
		} else {
			Iterator<Map.Entry<String, JsonNode>> it = selection.fields();
			while (it.hasNext()) {
				Map.Entry<String, JsonNode> entry = it.next();
				SubsetAndMetadataNode child = null;
				if (parent.getChildren() == null || 
						!parent.getChildren().containsKey(entry.getKey())) {
					child = new SubsetAndMetadataNode();
					parent.addChild(entry.getKey(), child);
					parent.setNeedSubsetInChildren(true);
				} else {
					// we will only get here if you run prepareWsSubsetTree twice, which does
					// not happen under any current code path
					child = parent.getChildren().get(entry.getKey());
					parent.setNeedSubsetInChildren(true);
				}
				prepareWsSubsetTree(entry.getValue(), keysOf, child);
			}
		}
	}

	/**
	 * Add the metadata selection to the parsing tree.  The metadata selection can ONLY be at the top level fields
	 * and can ONLY be used to extract the field value (if a scalar) as a string, or extract the length of a map (i.e. object) or
	 * array or string.  If you extend this to add lower level selections, you must revise the extraction logic!  Namely,
	 * behavior will not be correct if there is a metadata selection at a lower level, and a subdata extraction at a higher level.
	 * 
	 */
	private static void prepareMetadataSelectionTree(MetadataExtractionHandler metadataExtractionHandler, SubsetAndMetadataNode parent) {
		// currently, we can only extract fields from the top level
		JsonNode selection = metadataExtractionHandler.getMetadataSelection();
		Iterator<Map.Entry<String, JsonNode>> it = selection.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> entry = it.next();
			String metadataName = entry.getKey();
			String expression = entry.getValue().asText().trim();

			// evaluate the metadata selection expression (right now we only support descending into structures, and the length(f) function)
			boolean getLength = false;
			if(expression.startsWith("length(") && expression.endsWith(")")) {
				expression = expression.substring(7);
				expression = expression.substring(0, expression.length()-1);
				getLength = true;
			}
			
			String [] expTokens = expression.split("\\.");
			SubsetAndMetadataNode currentNode = parent;
			for(int k=0; k<expTokens.length; k++) {
				SubsetAndMetadataNode childNode = currentNode.getChild(expTokens[k]);
				if(childNode==null) {
					childNode = new SubsetAndMetadataNode();
					currentNode.addChild(expTokens[k], childNode);
				}
				currentNode = childNode;
			}
			if(getLength) {
				currentNode.addNeedLengthForMetadata(metadataName);
			} else {
				currentNode.addNeedValueForMetadata(metadataName);
			}
		}
	}

	/**
	 * This method is recursively processing block of json data (map, array of scalar) when
	 * first token of this block was already taken and stored in current variable. This is
	 * typical for processing array elements because we need to read first token in order to
	 * know is it the end of array of not. For maps/objects there is such problem because
	 * we read field token before processing value block.
	 * 
	 * This method returns the number of (top-level) elements in the object or list, which
	 * may be needed in computing metadata.
	 */
	private static long writeTokensFromCurrent(TokenSequenceProvider jts, JsonToken current, 
			JsonGenerator jgen) throws IOException, TypedObjectExtractionException {
		JsonToken t = current;
		writeCurrentToken(jts, t, jgen);
		long n_elements = 0;
		if (t == JsonToken.START_OBJECT) {
			while (true) {
				t = jts.nextToken();
				writeCurrentToken(jts, t, jgen);
				if (t == JsonToken.END_OBJECT)
					break;
				if (t != JsonToken.FIELD_NAME)
					throw new TypedObjectExtractionException("Error parsing json format: " + t.asString());
				t = jts.nextToken();
				n_elements++;
				writeTokensFromCurrent(jts, t, jgen);
			}
		} else if (t == JsonToken.START_ARRAY) {
			while (true) {
				t = jts.nextToken();
				if (t == JsonToken.END_ARRAY) {
					writeCurrentToken(jts, t, jgen);
					break;
				}
				n_elements++;
				writeTokensFromCurrent(jts, t, jgen);
			}
		}
		return n_elements;
	}

	/**
	 * when metadata extraction of the length of an object/array is required, but we do not
	 * need to write the data to the subdata stream, then we can call this to traverse the
	 * data and compute the length.
	 */
	private static long countElementsInCurrent(TokenSequenceProvider jts, JsonToken current, 
			JsonGenerator jgen) throws IOException, TypedObjectExtractionException {
		JsonToken t = current;
		long n_elements = 0;
		if (t == JsonToken.START_OBJECT) {
			while (true) {
				t = jts.nextToken();
				if (t == JsonToken.END_OBJECT)
					break;
				if (t != JsonToken.FIELD_NAME)
					throw new TypedObjectExtractionException("Error parsing json format: " + t.asString());
				t = jts.nextToken();
				n_elements++;
				countElementsInCurrent(jts, t, jgen);
			}
		} else if (t == JsonToken.START_ARRAY) {
			while (true) {
				t = jts.nextToken();
				if (t == JsonToken.END_ARRAY) {
					break;
				}
				n_elements++;
				countElementsInCurrent(jts, t, jgen);
			}
		}
		return n_elements;
	}


	/**
	 * Method processes (writes into output token stream - jgen) only one token.
	 */
	private static JsonToken writeCurrentToken(TokenSequenceProvider jts, JsonToken current, 
			JsonGenerator jgen) throws IOException, TypedObjectExtractionException {
		JsonToken t = current;
		if (t == JsonToken.START_ARRAY) {
			jgen.writeStartArray();
		} else if (t == JsonToken.START_OBJECT) {
			jgen.writeStartObject();
		} else if (t == JsonToken.END_ARRAY) {
			jgen.writeEndArray();
		} else if (t == JsonToken.END_OBJECT) {
			jgen.writeEndObject();
		} else if (t == JsonToken.FIELD_NAME) {
			jgen.writeFieldName(jts.getText());
		} else if (t == JsonToken.VALUE_NUMBER_INT) {
			// VALUE_NUMBER_INT type corresponds to set of integer types
			Number value = jts.getNumberValue();
			if (value instanceof Short) {
				jgen.writeNumber((Short)value);
			} else if (value instanceof Integer) {
				jgen.writeNumber((Integer)value);
			} else if (value instanceof Long) {
				jgen.writeNumber((Long)value);
			} else if (value instanceof BigInteger) {
				jgen.writeNumber((BigInteger)value);
			} else {
				jgen.writeNumber(value.longValue());
			}
		} else if (t == JsonToken.VALUE_NUMBER_FLOAT) {
			// VALUE_NUMBER_FLOAT type corresponds to set of floating point types
			Number value = jts.getNumberValue();
			if (value instanceof Float) {
				jgen.writeNumber((Float)value);
			} else if (value instanceof Double) {
				jgen.writeNumber((Double)value);
			} else if (value instanceof BigDecimal) {
				jgen.writeNumber((BigDecimal)value);
			} else {
				jgen.writeNumber(value.doubleValue());
			}
		} else if (t == JsonToken.VALUE_STRING) {
			jgen.writeString(jts.getText());
		} else if (t == JsonToken.VALUE_NULL) {
			jgen.writeNull();
		} else if (t == JsonToken.VALUE_FALSE) {
			jgen.writeBoolean(false);
		} else if (t == JsonToken.VALUE_TRUE) {
			jgen.writeBoolean(true);
		} else {
			throw new TypedObjectExtractionException("Unexpected token type: " + t);
		}
		return t;
	}

	/**
	 * If some part of the json data is not mentioned in traversal tree, we can skip all tokens of it
	 */
	private static void skipChildren(TokenSequenceProvider jts, JsonToken current) 
			throws IOException, JsonParseException {
		JsonToken t = current;
		if (t == JsonToken.START_OBJECT) {
			while (true) {
				t = jts.nextToken();
				if (t == JsonToken.END_OBJECT)
					break;
				t = jts.nextToken();
				skipChildren(jts, t);
			}
		} else if (t == JsonToken.START_ARRAY) {
			while (true) {
				t = jts.nextToken();
				if (t == JsonToken.END_ARRAY)
					break;
				skipChildren(jts, t);
			}
		}
	}


	/**
	 * helper method to add the length of an array/object to the metadata for every metadata named in metadataHandler
	 */
	private static void addLengthMetadata(long length, SubsetAndMetadataNode selection, MetadataExtractionHandler metadataHandler) 
			throws ExceededMaxMetadataSizeException {
		List<String> metadataNames = selection.getNeedLengthForMetadata();
		for(String name:metadataNames) {
			metadataHandler.saveMetadata(name,Long.toString(length));
		}
	}
	private static void addNullLengthMetadata(SubsetAndMetadataNode selection, MetadataExtractionHandler metadataHandler) 
			throws ExceededMaxMetadataSizeException {
		List<String> metadataNames = selection.getNeedLengthForMetadata();
		for(String name:metadataNames) {
			metadataHandler.saveMetadata(name,"NaN");
		}
	}

	/**
	 * helper method to add the value of an array/object to the metadata for every metadata named in metadataHandler
	 */
	private static void addValueMetadata(String value, SubsetAndMetadataNode selection, MetadataExtractionHandler metadataHandler) 
			throws ExceededMaxMetadataSizeException {
		List<String> metadataNames = selection.getNeedValueForMetadata();
		for(String name:metadataNames) {
			metadataHandler.saveMetadata(name,value);
		}
	}

	/*
	 * This is main recursive method for tracking current token place in searchabl/metadata schema tree
	 * and making decisions whether or not we need to process this token or block of tokens or just skip it.
	 */
	private static void extractFieldsWithOpenToken(
			TokenSequenceProvider jts,
			JsonToken current, 
			SubsetAndMetadataNode selection,
			MetadataExtractionHandler metadataHandler,
			JsonGenerator jgen,
			List<String> path) 
					throws IOException, TypedObjectExtractionException, ExceededMaxMetadataSizeException {

		JsonToken t = current;
		// We observe the opening of a mapping/object in the JSON data
		if (t == JsonToken.START_OBJECT) {
			// we need everything at this node and below
			if (selection.isNeedAll()) {
				if(selection.hasChildren()) {  // if it has children, then we must need some specific metadata below and everything that is here
					// we just go through the object and write everything....
					writeCurrentToken(jts, t, jgen);
					long n_elements = 0;
					while (true) {
						t = jts.nextToken();
						n_elements++;
						if (t == JsonToken.END_OBJECT) {
							writeCurrentToken(jts, t, jgen);
							break;
						}
						if (t != JsonToken.FIELD_NAME)
							throw new TypedObjectExtractionException("Error parsing json format: " + t.asString());
						
						// we have to write everything, so do that...
						writeCurrentToken(jts, t, jgen);
						String fieldName = jts.getText();
						
						// read first token of value block in order to prepare state for recursive extractFieldsWithOpenToken call
						t = jts.nextToken();
						path.add(fieldName);
						
						// if child exists, then we pass that along, but if the child does not exist, then we need to
						// create a child node so that we extract that as well (remember, we need every field here)
						SubsetAndMetadataNode child = selection.getChild(fieldName);
						if(child!=null) {
							child.setNeedAll(true); // we need to tell the next recursive call that we need all fields below
							extractFieldsWithOpenToken(jts, t, child, metadataHandler, jgen, path);
						} else {
							// we need to create a dummy node to just get everything below this node recursively 
							SubsetAndMetadataNode fakeChild = new SubsetAndMetadataNode();
							fakeChild.setNeedAll(true);
							extractFieldsWithOpenToken(jts, t, fakeChild, metadataHandler, jgen, path);
						}
						// remove field from end of path branch
						path.remove(path.size() - 1);
					}
					addLengthMetadata(n_elements, selection, metadataHandler);
				} else { // if it does not have children, then we just extract everything
					long n_elements = writeTokensFromCurrent(jts, t, jgen);
					addLengthMetadata(n_elements, selection, metadataHandler);
				}
			}

			// we need only keys of this node
			else if (selection.isNeedKeys()) {
				// if it has children, then we must need some metadata value below
				// BUT! we do not support extracting metadata from a mapping so this is impossible
				//if(selection.hasChildren()) { 
					// handle case where metadata selection is inside a mapping - this is not allowed right now 
				//}
				jgen.writeStartArray();  // write in output start of array instead of start of object
				long n_elements = 0;
				while (true) {
					t = jts.nextToken();
					if (t == JsonToken.END_OBJECT) {
						jgen.writeEndArray();  // write in output end of array instead of end of object
						break;
					}
					if (t != JsonToken.FIELD_NAME)
						throw new TypedObjectExtractionException("Error parsing json format: " + t.asString());
					String fieldName = jts.getText();
					jgen.writeString(fieldName);  // write in output field name
					t = jts.nextToken();
					n_elements++;

					// it is impossible for now to have the selection contain children, because
					// we do not allow subset below a 'keys_of' selection, and we only allow top-level metadata
					// instead for now, we just skip the children
					skipChildren(jts, t);
				}
				// we may need the number of elements in this mapping, in which case we add it
				addLengthMetadata(n_elements, selection, metadataHandler);
			}

			// we have children, and these children have restrictions in subdata, so we go down the tree
			else if (selection.hasChildren()) {
				
				// we will remove visited keys from selectedFields, and we could check that we visited every node at the end
				Set<String> selectedFields = new LinkedHashSet<String>(selection.getChildren().keySet());
				boolean all = false;
				SubsetAndMetadataNode allChild = null;

				if (selectedFields.contains("*")) {
					all = true;
					selectedFields.remove("*"); // detach the subtree below the *
					allChild = selection.getChildren().get("*");
					if (selectedFields.size() > 0) {
						// NOTE! if we allow metadata extraction from a map, then this is not an error...
						throw new TypedObjectExtractionException("WS subset path with * contains other " +
								"fields (" + selectedFields + ") at " + SubdataExtractor.getPathText(path));
					}
				}
				// process first token standing for start of object, only write if we need subset data below
				if(selection.isNeedSubsetInChildren()) {
					writeCurrentToken(jts, t, jgen);
				}
				long n_elements = 0;
				while (true) {
					t = jts.nextToken();
					n_elements++;
					if (t == JsonToken.END_OBJECT) {
						if(selection.isNeedSubsetInChildren()) {
							writeCurrentToken(jts, t, jgen);
						}
						break;
					}
					if (t != JsonToken.FIELD_NAME)
						throw new TypedObjectExtractionException("Error parsing json format: " + t.asString());
					String fieldName = jts.getText();
					if (all || selectedFields.contains(fieldName)) {
						SubsetAndMetadataNode child = selection.getChild(fieldName);
						// tricky logic here: if we need all, then we are in a mapping and we need to write this field name
						// if we are not all, then the child must be defined (or else we get some error). Then we can
						// see if we need to write out anything related the child, such as the keys, everything, or something
						// below.  If we do, then we write the token, if we do not, then all we need from this child is
						// metadata, which means we do not have to write anything to the subset for this node but we must
						// still recurse down...
						if(all) { writeCurrentToken(jts, t, jgen); }
						else {
							if(child.isNeedSubsetInChildren() || child.isNeedAll() || child.isNeedKeys()) {
								writeCurrentToken(jts, t, jgen);
							}
						}
						// read first token of value block in order to prepare state for recursive 
						// extractFieldsWithOpenToken call
						t = jts.nextToken();
						// add field to the end of path branch
						path.add(fieldName);
						// we cannot have 'all' and select metadata below, because we cannot enter a mapping and
						// all (ie the * notation) can only be used in the case of mappings to select all keys
						extractFieldsWithOpenToken(jts, t, all ? allChild : child, metadataHandler, jgen, path);
						// remove field from end of path branch
						path.remove(path.size() - 1);
						if (!all)
							selectedFields.remove(fieldName);
					} else {
						// otherwise we skip value following after field
						t = jts.nextToken();
						skipChildren(jts, t);
					}
				}
				addLengthMetadata(n_elements, selection, metadataHandler); // add length of object to metadata if needed
				
				// note: fields can be optional, so if we did not visit a selected field, it is just left out of the subdata
				// we do not need to check here to see if there are paths we did not visit
			} 

			// otherwise we need (at most) just the length metadata field
			else {
				// there are no children, and we didn't need subdata here, so the only thing possible is getting metadata
				long n_elements = countElementsInCurrent(jts,t,jgen);
				addLengthMetadata(n_elements, selection, metadataHandler);
			}
		}



		// We observe an array/list starting in the real JSON data
		else if (t == JsonToken.START_ARRAY) {
			if (selection.hasChildren()) {  // we have some restrictions for array item positions in selection
				Set<String> selectedFields = new LinkedHashSet<String>(
						selection.getChildren().keySet());
				SubsetAndMetadataNode allChild = null;
				// now we support only '[*]' keyword which means all elements, and we cannot get metadata from inside a list/tuple
				if (!selectedFields.contains("[*]"))
					throw new TypedObjectExtractionException("WS subset path doesn't contain [*] on array " +
							"level (" + selectedFields + ") at " + SubdataExtractor.getPathText(path));
				selectedFields.remove("[*]");
				allChild = selection.getChildren().get("[*]");
				if (selectedFields.size() > 0) {
					// NOTE! if we allow metadata extraction from an array element, then this is not an error...
					throw new TypedObjectExtractionException("WS subset path with [*] contains other " +
							"fields (" + selectedFields + ") at " + SubdataExtractor.getPathText(path));
				}
				writeCurrentToken(jts, t, jgen);  // write start of array into output
				long n_elements = 0;
				for (int pos = 0; ; pos++) {
					t = jts.nextToken();
					n_elements++;
					if (t == JsonToken.END_ARRAY) {
						writeCurrentToken(jts, t, jgen);
						break;
					}
					// add element position to the end of path branch
					path.add("" + pos);
					// process value of this element recursively
					extractFieldsWithOpenToken(jts, t, allChild, metadataHandler, jgen, path);
					// remove field from end of path branch
					path.remove(path.size() - 1);
				}
				addLengthMetadata(n_elements, selection, metadataHandler); // add length of array to metadata if needed
			} else {  // we need the whole array
				if (selection.isNeedKeys())
					throw new TypedObjectExtractionException("WS subset path contains keys-of level for array " +
							"value at " + SubdataExtractor.getPathText(path));
				// need all elements
				long n_elements = 0;
				if(selection.isNeedAll()) { // need all elements, possibly also the length
					n_elements = writeTokensFromCurrent(jts, t, jgen);
				} else { // only need the length of the object
					n_elements = countElementsInCurrent(jts,t,jgen);
				}
				addLengthMetadata(n_elements, selection, metadataHandler);
			}
		} else {	// we observe scalar value (text, integer, double, boolean, null) in real json data
			if (selection.hasChildren())
				throw new TypedObjectExtractionException("WS subset path contains non-empty level for scalar " +
						"value at " + SubdataExtractor.getPathText(path));
			if (selection.isNeedKeys())
				throw new TypedObjectExtractionException("WS subset path contains keys-of level for scalar " +
						"value at " + SubdataExtractor.getPathText(path));

			if (selection.isNeedAll()) // if this is set, then save the data to the output stream
				writeCurrentToken(jts, t, jgen);

			// first handle the length of metadata extraction
			if(t==JsonToken.VALUE_STRING) { // if a string, add the length to metadata if it was selected
				addLengthMetadata(jts.getText().length(), selection, metadataHandler);
			} else if(t==JsonToken.VALUE_NULL) {
				// value is null, but we should still add it so that the metadata is not just completely missing
				addNullLengthMetadata(selection, metadataHandler);
			} else {
				// if we got here, then the value is not a string and is not null, so this is not valid (although
				// this should be caught as an error during type registration if using this lib with the workspace)
				if (!selection.getNeedLengthForMetadata().isEmpty())
					throw new TypedObjectExtractionException("WS metadata path contains length() method called on a scalar " +
							"value at " + SubdataExtractor.getPathText(path));
			}
			
			// get the actual value
			String metadataValue = jts.getText();
			addValueMetadata(metadataValue, selection, metadataHandler); // add the value to metadata if needed
		}
	}
}

package edu.stanford.nlp.kbp.slotfilling.process;

import edu.stanford.nlp.ie.machinereading.structure.MachineReadingAnnotations.*;
import edu.stanford.nlp.ie.machinereading.structure.Span;
import edu.stanford.nlp.kbp.common.*;
import edu.stanford.nlp.kbp.common.KBPAnnotations.*;
import edu.stanford.nlp.ling.CoreAnnotations.*;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.stats.ClassicCounter;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.stats.Counters;
import edu.stanford.nlp.trees.TreeCoreAnnotations.*;
import edu.stanford.nlp.ie.machinereading.structure.EntityMention;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.Annotator;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.Generics;
import edu.stanford.nlp.util.logging.Redwood;

import java.util.*;

/**
 * Annotates slot mentions in a set of sentences.
 *
 * @author Gabor Angeli
 */
public class SlotMentionAnnotator implements Annotator {

  private static Redwood.RedwoodChannels logger = Redwood.channels("SlotAnn");

  /**
   * A list of personal pronouns, excluding possessives (his, her, my), as these are not likely to be
   * slot referents.
   * These are largely taken from Coref's {@link edu.stanford.nlp.dcoref.Dictionaries},
   * but pruned to be more high-precision {@link NERTag#PERSON} pronouns.
   */
  public static final Set<String> personPronouns = Generics.newHashSet(Arrays.asList(new String[]{
      "he", "him", "himself", "she", "her", "herself", "her", "themself", "themselves", "'em", "themselves",
      "you", "yourself", "yourselves",
      "i", "me", "myself", "ourself", "ourselves",
  }));

  @SuppressWarnings("unchecked")
  @Override
  public void annotate(Annotation annotation) {
    for (CoreMap sentence : annotation.get(SentencesAnnotation.class)) {
      // Get prerequisite data; see requires()
      List<EntityMention> entityMentions = sentence.get(EntityMentionsAnnotation.class);
      if (entityMentions == null) {
        entityMentions = Collections.EMPTY_LIST;
      }
      assert entityMentions != null;

      // find additional NEs (i.e., slot candidates) that are conditional on the
      // EntityMentions extracted
      // currently this finds candidates for per:title from the base NPs that
      // include the EntityMention
      findConditionalNamedEntities(entityMentions, sentence);  // TODO(gabor) can we make do without this?

      // Find and set slot mentions
      List<EntityMention> slotMentions = extractSlotMentions(sentence, entityMentions);
      sentence.set(SlotMentionsAnnotation.class, slotMentions);
    }
  }

  @Override
  public Set<Requirement> requirementsSatisfied() {
    return new HashSet<>(Arrays.asList(new Requirement[]{new Requirement("postir.slotmentions")}));
  }

  @Override
  public Set<Requirement> requires() {
    return new HashSet<>(Arrays.asList(new Requirement[]{new Requirement("postir.entitymentions")}));
  }




  //
  // Block for extracting slot mentions
  //
  private List<EntityMention> extractSlotMentions(CoreMap sentence, final Collection<EntityMention> entityMentions) {
    // Setup
    Set<Span> entitySpans = new HashSet<>();
    for (EntityMention mention : entityMentions) {
      entitySpans.add(mention.getExtent());
    }
    // (mask out the entity mentions in the sentence)
    boolean[] entityMask = new boolean[sentence.get(TokensAnnotation.class).size()];
    for (Span span : entitySpans) {
      for (int i = span.start(); i < span.end(); ++i) {
        entityMask[i] = true;
      }
    }
    // (get the entity type)
    List<CoreLabel> tokens = sentence.get(TokensAnnotation.class);
    Counter<String> entityNERVotes = new ClassicCounter<>();
    for (EntityMention mention : entityMentions) {
      for (int i : mention.getHead()) {
        entityNERVotes.incrementCount(tokens.get(i).ner());
      }
    }
    Maybe<String> entityNER = Maybe.Nothing();
    if (entityNERVotes.size() > 0 && !Counters.argmax(entityNERVotes).equals(Props.NER_BLANK_STRING)) {
      entityNER = Maybe.Just(Counters.argmax(entityNERVotes));
    }

    // Augment NER tags for coreferent mentions
    for (CoreLabel token : tokens) {

      // Try to get the NER for the antecedent
      // 3 conditions: It should have an antecedent, the antecedent should be a proper noun, and the word should be a verifiable entity.
      // This last condition means that it is either:
      //   - A personal pronoun ( -> PERSON )
      //   - A location, as per world knowledge
      String antecedent = token.get(AntecedentAnnotation.class);
      if (antecedent != null && antecedent.length() == 0) { antecedent = null; }
      if (token.ner().equals(Props.NER_BLANK_STRING) && token.tag().equals("PRP") && antecedent != null &&
          Character.isUpperCase(antecedent.charAt(0))) {
        if (personPronouns.contains(token.word().toLowerCase())) {
          token.setNER(NERTag.PERSON.name);
        } else if (Utils.geography().isValidCity(antecedent)) {
          token.setNER(NERTag.CITY.name);
        } else if (Utils.geography().isValidRegion(antecedent)) {
          token.setNER(NERTag.STATE_OR_PROVINCE.name);
        } else if (Utils.geography().isValidCountry(antecedent)) {
          token.setNER(NERTag.COUNTRY.name);
        }
      }
    }

    // Find slot mentions by iterating over tokens
    List<EntityMention> slots = new ArrayList<>();
    for (int start = 0; start < tokens.size(); ++start) {
      CoreLabel token = tokens.get(start);
      String ner = token.ner();
      String pos = token.tag();
      String antecedent = token.get(AntecedentAnnotation.class);

      // valid candidates must be:
      //   * NEs,
      //   * not the query entity,
      //   * not an extension of the NER tag of an query entity (e.g., "[George Bush] Sr. -- Sr. should not be a slot")
      //   * starting on a reasonable POS
      if (ner == null || "".equals(ner) || ner.equals(Props.NER_BLANK_STRING) ||
          entityMask[start] ||
          (start > 0 && entityMask[start] && entityNER.equalsOrElse(ner, false)) ||
          pos.equals("IN") || pos.equals("DT") || pos.equals("RB") || pos.equals("EX") || pos.equals("POS")) {
        continue;
      }

      //  tokens.get(start).word());
      int end = start + 1;
      while (end < tokens.size()) {
        CoreLabel crt = tokens.get(end);
        if (antecedent == null) { antecedent = crt.get(AntecedentAnnotation.class); }
        if (crt.ner() == null || !crt.ner().equals(ner) || entityMask[end]) {
          break;
        }
        end++;
      }

      // fix up last POS, if invalid
      while (end > start + 1 && (tokens.get(end - 1).tag().equals("IN") || tokens.get(end - 1).tag().equals("DT") ||
          tokens.get(end - 1).tag().equals("RB") || tokens.get(end - 1).tag().equals("EX") || tokens.get(end - 1).tag().equals("POS"))) {
        end -= 1;
      }

      // Don't take dangling NER tags from the edge of an entity (e.g., "[George Bush] Sr. -- Sr. should not be a slot")
      if (end < tokens.size() - 1 && entityMask[end] && entityNER.equalsOrElse(tokens.get(end - 1).ner(), false)) {
        continue;
      }

      // if not valid, move on
      for (NERTag nerTag : NERTag.fromString(ner)) { // TODO(gabor) multiple named entity types?
        Span span = new Span(start, end);
        if (!Span.overlaps(span, entitySpans) && Utils.closeEnough(span, entitySpans)) {
          assert ner != null && !ner.trim().equalsIgnoreCase("") && !ner.equals(Props.NER_BLANK_STRING);
          EntityMention em = new EntityMention(Utils.makeEntityMentionId(Maybe.<String>Nothing()), sentence, span, span, ner, null, ner);
          assert Utils.getNERTag(em).isDefined();
          if (antecedent != null && nerTag != NERTag.DATE && nerTag != NERTag.NUMBER) { em.setNormalizedName(antecedent); }
          assert em.getType() != null && !em.getType().trim().equalsIgnoreCase("") && !em.getType().equals(Props.NER_BLANK_STRING);
          if (Props.KBP_VERBOSE) { logger.debug("found slot mention: " + em); }
          slots.add(em);
        }
      }

      start = end - 1;
    }
    return slots;
  }


  //
  // Block for finding conditional named entities
  //

  private static void findConditionalNamedEntities(List<EntityMention> mentions, CoreMap sentence) {
    Tree tree = sentence.get(TreeAnnotation.class);
    if (tree == null) {
      logger.err("No tree in sentence: " + CoreMapUtils.sentenceToMinimalString(sentence));
      return;
    }

    // make sure the tree contains CoreLabels and tokens are indexed
    CoreMapUtils.convertToCoreLabels(tree);
    if (!((CoreLabel) tree.label()).containsKey(BeginIndexAnnotation.class)) { tree.indexSpans(0); }

    // find MODIFIERs
    findModifiers(mentions, sentence, tree);
  }

  private static void findModifiers(List<EntityMention> mentions, CoreMap sentence, Tree tree) {
    List<CoreLabel> tokens = sentence.get(TokensAnnotation.class);

    for (EntityMention mention : mentions) {
      Span span = mention.getHead();
      Tree subtree = findTreeHeadedBySpan(tree, span);
      if (subtree == null) continue;
      int start = ((CoreLabel) subtree.label()).get(BeginIndexAnnotation.class);
      int end = span.start();
      assert (start <= end);

      int modifierStart = -1;
      int modifierEnd = -1;
      for (int i = start; i < end; i++) {
        CoreLabel token = tokens.get(i);
        if (modifierStart == -1 && token.tag().startsWith("NN")
            && token.ner().equals("O")) {
          modifierStart = i;
        } else if (modifierStart >= 0
            && (!token.tag().startsWith("NN") || !token.ner().equals("O"))) {
          modifierEnd = i;
        }
      }
      if (modifierStart >= 0) {
        if (modifierEnd == -1) modifierEnd = end;
        StringBuilder os = new StringBuilder();
        for (int i = modifierStart; i < modifierEnd; i++) {
          tokens.get(i).setNER("MODIFIER");
          if (i > modifierStart) os.append(" ");
          os.append(tokens.get(i).word());
        }
        if (Props.KBP_VERBOSE) {
          logger.debug("Found modifier [" + os.toString() + "] for entity [" + mention.getExtentString()
              + "] in sentence: " + CoreMapUtils.sentenceToMinimalString(sentence));
        }
      }
    }
  }


  private static Tree findTreeHeadedBySpan(Tree tree, Span span) {
    for (Tree kid : tree.children()) {
      if (kid == null) continue;
      Tree match = findTreeHeadedBySpan(kid, span);
      if (match != null) return match;
    }

    CoreLabel l = (CoreLabel) tree.label();
    if (l != null && l.has(BeginIndexAnnotation.class) && l.has(EndIndexAnnotation.class)) {
      String constLabel = l.value();
      int myStart = l.get(BeginIndexAnnotation.class);
      int myEnd = l.get(EndIndexAnnotation.class);
      if (constLabel.equals("NP") && myStart <= span.start() && myEnd >= span.end()) {
        return tree;
      }
    }

    return null;
  }
}

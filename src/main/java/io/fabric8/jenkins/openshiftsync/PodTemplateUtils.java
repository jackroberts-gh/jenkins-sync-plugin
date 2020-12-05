package io.fabric8.jenkins.openshiftsync;

import com.thoughtworks.xstream.XStreamException;
import hudson.util.XStream2;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.openshift.api.model.Image;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.api.model.ImageStreamStatus;
import io.fabric8.openshift.api.model.ImageStreamTag;
import io.fabric8.openshift.api.model.TagReference;
import jenkins.model.Jenkins;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.csanchez.jenkins.plugins.kubernetes.PodVolumes;
import org.csanchez.jenkins.plugins.kubernetes.model.KeyValueEnvVar;
import org.csanchez.jenkins.plugins.kubernetes.model.TemplateEnvVar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static java.util.logging.Level.FINE;

public class PodTemplateUtils {

  protected static final String cmType = "ConfigMap";
  protected static final String isType = "ImageStream";
  static final String IMAGESTREAM_TYPE = isType;
  private static final String PT_NAME_CLAIMED = "The event for %s | %s | %s that attempts to add the pod template %s was ignored because a %s previously created a pod template with the same name";
  private static final String PT_NOT_OWNED = "The event for %s | %s | %s that no longer includes the pod template %s was ignored because the type %s was associated with that pod template";
  private static final Logger LOGGER = Logger.getLogger(PodTemplateUtils.class.getName());
  private static final String PARAM_FROM_ENV_DESCRIPTION = "From OpenShift Build Environment Variable";
  static final String SLAVE_LABEL = "slave-label";
  private static final String SPECIAL_IST_PREFIX = "imagestreamtag:";
  private static final int SPECIAL_IST_PREFIX_IDX = SPECIAL_IST_PREFIX.length();
  protected static ConcurrentHashMap<String, List<PodTemplate>> trackedPodTemplates = new ConcurrentHashMap<String, List<PodTemplate>>();
  protected static ConcurrentHashMap<String, String> podTemplateToApiType = new ConcurrentHashMap<String, String>();

  protected static boolean hasOneAndOnlyOneWithSomethingAfter(String str, String substr) {
    return str.contains(substr)
      && str.indexOf(substr) == str.lastIndexOf(substr)
      && str.indexOf(substr) < str.length();
  }

  public static PodTemplate podTemplateInit(String name, String image, String label) {
    LOGGER.info("Initializing PodTemplate: "+name);
    PodTemplate podTemplate = new PodTemplate(image, new ArrayList<PodVolumes.PodVolume>());
    // with the above ctor guarnateed to have 1 container
    // also still force our image as the special case "jnlp" container for
    // the KubernetesSlave;
    // attempts to use the "jenkinsci/jnlp-slave:alpine" image for a
    // separate jnlp container
    // have proved unsuccessful (could not access gihub.com for example)
    podTemplate.getContainers().get(0).setName("jnlp");
    // podTemplate.setInstanceCap(Integer.MAX_VALUE);
    podTemplate.setName(name);
    podTemplate.setLabel(label);
    podTemplate.setAlwaysPullImage(true);
    podTemplate.setCommand("");
    podTemplate.setArgs("${computer.jnlpmac} ${computer.name}");
    podTemplate.setRemoteFs("/tmp");
    String podName = System.getenv().get("HOSTNAME");
    if (podName != null) {
      Pod pod = getAuthenticatedOpenShiftClient().pods().withName(podName).get();
      if (pod != null) {
        podTemplate.setServiceAccount(pod.getSpec().getServiceAccountName());
      }
    }
    return podTemplate;
  }


  public static void removePodTemplate(PodTemplate podTemplate) {
    KubernetesCloud kubeCloud = JenkinsUtils.getKubernetesCloud();
    if (kubeCloud != null) {
      LOGGER.info("Removing PodTemplate: " + podTemplate.getName());
      // NOTE - PodTemplate does not currently override hashCode, equals,
      // so
      // the KubernetsCloud.removeTemplate currently is broken;
      // kubeCloud.removeTemplate(podTemplate);
      List<PodTemplate> list = kubeCloud.getTemplates();
      Iterator<PodTemplate> iter = list.iterator();
      while (iter.hasNext()) {
        PodTemplate pt = iter.next();
        if (pt.getName().equals(podTemplate.getName())) {
          iter.remove();
        }
      }
      // now set new list back into cloud
      kubeCloud.setTemplates(list);
      try {
        // pedantic mvn:findbugs
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins != null)
          jenkins.save();
      } catch (IOException e) {
        LOGGER.log(Level.SEVERE, "removePodTemplate", e);
      }

      if (LOGGER.isLoggable(Level.FINE)) {
        LOGGER.fine("PodTemplates now:");
        for (PodTemplate pt : kubeCloud.getTemplates()) {
          LOGGER.fine(pt.getName());
        }
      }
    }
  }

  public static synchronized List<PodTemplate> getPodTemplates() {
    KubernetesCloud kubeCloud = JenkinsUtils.getKubernetesCloud();
    if (kubeCloud != null) {
      // create copy of list for more flexiblity in loops
      ArrayList<PodTemplate> list = new ArrayList<PodTemplate>();
      list.addAll(kubeCloud.getTemplates());
      return list;
    } else {
      return null;
    }
  }

  public static synchronized boolean hasPodTemplate(PodTemplate incomingPod) {
    String name = incomingPod.getName();
    if (name == null)
      return false;
    String image = incomingPod.getImage();
    if (image == null)
      return false;
    KubernetesCloud kubeCloud = JenkinsUtils.getKubernetesCloud();
    if (kubeCloud != null) {
      List<PodTemplate> list = kubeCloud.getTemplates();
      for (PodTemplate pod : list) {
        if (name.equals(pod.getName()) && image.equals(pod.getImage()))
          return true;
      }
    }
    return false;
  }

  public static synchronized void addPodTemplate(PodTemplate podTemplate) {
    // clear out existing template with same name; k8s plugin maintains
    // list, not map
    removePodTemplate(podTemplate);
    KubernetesCloud kubeCloud = JenkinsUtils.getKubernetesCloud();
    if (kubeCloud != null) {
      LOGGER.info("Adding PodTemplate: " + podTemplate.getName());
      kubeCloud.addTemplate(podTemplate);
      try {
        // pedantic mvn:findbugs
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins != null)
          jenkins.save();
      } catch (IOException e) {
        LOGGER.log(Level.SEVERE, "addPodTemplate", e);
      }
    }
  }

  protected static void purgeTemplates(BaseWatcher baseWatcher, String type, String uid, String apiObjName, String namespace) {
    LOGGER.info("Purging PodTemplates for from Configmap with Uid "+uid);
    for (PodTemplate podTemplate : trackedPodTemplates.get(uid)) {
      // we should not have included any pod templates we did not
      // mark the type for, but we'll check just in case
      removePodTemplate(LOGGER, PT_NOT_OWNED, type, apiObjName, namespace, podTemplate);
    }
    trackedPodTemplates.remove(uid);
  }

  protected static void updateTrackedPodTemplatesMap(String uid, List<PodTemplate> finalSlaveList) {
    if (finalSlaveList != null && finalSlaveList.size() > 0)
      trackedPodTemplates.put(uid, finalSlaveList);
  }

  // Adds PodTemplate to the List<PodTemplate> correspoding to the ConfigMap of given uid
  protected static void trackPodTemplates(String uid, List<PodTemplate> podTemplatesToTrack) {
    trackedPodTemplates.put(uid, podTemplatesToTrack);
  }

  // Adds PodTemplate to the List<PodTemplate> correspoding to the ConfigMap of given uid and Deletes from Jenkins
  protected static List<PodTemplate> onlyTrackPodTemplate(BaseWatcher baseWatcher, String type, String apiObjName, String namespace, List<PodTemplate> podTemplates, PodTemplate podTemplate) {
    String name = podTemplate.getName();
    // we allow configmap overrides of maven and nodejs, but not imagestream ones
    // as they are less specific/defined wrt podTemplate fields

    if (isReservedPodTemplateName(name) && isType.equals(type))
      return null;
    // for imagestreams, if the core image has not changed, we avoid
    // the remove/add pod template churn and multiple imagestream events
    // come in for activity that does not affect the pod template
    if (type.equals(isType) && hasPodTemplate(podTemplate))
      return null;
    // once a CM or IS claims a name, it gets to keep it until it is remove or un-labeled
    String ret = podTemplateToApiType.putIfAbsent(name, type);
    // if not set, or previously set by an obj of the same type
    if (ret == null || ret.equals(type)) {
      removePodTemplate(podTemplate);
      podTemplates.add(podTemplate);
    } else {
      LOGGER.info(String.format(PT_NAME_CLAIMED, type, apiObjName, namespace, name, ret));
    }
    return podTemplates;
  }

  // Adds PodTemplate from Jenkins
  protected static void addPodTemplate(BaseWatcher baseWatcher, String type, String apiObjName, String namespace, List<PodTemplate> podTemplates, PodTemplate podTemplate) {
    String name = podTemplate.getName();
    // we allow configmap overrides of maven and nodejs, but not imagestream ones
    // as they are less specific/defined wrt podTemplate fields
    if (apiObjName != null && namespace != null && podTemplates != null){
      if (isReservedPodTemplateName(name) && isType.equals(type)) {
        return;
      }
      String ret = podTemplateToApiType.putIfAbsent(name, type);
      if (ret == null || ret.equals(type)) {
        addPodTemplate(podTemplate);
        podTemplates.add(podTemplate);
      } else {
        LOGGER.info(String.format(PT_NAME_CLAIMED, type, apiObjName, namespace, name, ret));
      }
    } else {
      podTemplateToApiType.put(name, type);
      addPodTemplate(podTemplate);
    }
  }

  // Delete a PodTemplate from Jenkins
  protected static void removePodTemplate(Logger LOGGER, String PT_NOT_OWNED, String type, String apiObjName, String namespace, PodTemplate podTemplate) {
    String name = podTemplate.getName();
    String t = podTemplateToApiType.get(name);
    if (t != null && t.equals(type)) {
      podTemplateToApiType.remove(name);
      removePodTemplate(podTemplate);
    } else {
      LOGGER.info(String.format(PT_NOT_OWNED, type, apiObjName, namespace, name, t));
    }
  }

  protected static boolean isReservedPodTemplateName(String name) {
    if (name.equals("maven") || name.equals("nodejs"))
      return true;
    return false;
  }

  protected static List<PodTemplate> getPodTemplatesListFromImageStreams(ImageStream imageStream) {
    List<PodTemplate> results = new ArrayList<PodTemplate>();
    // for IS, since we can check labels, check there
    ObjectMeta metadata = imageStream.getMetadata();
    String isName = metadata.getName();
    if (hasSlaveLabelOrAnnotation(metadata.getLabels())) {
      ImageStreamStatus status = imageStream.getStatus();
      String repository = status.getDockerImageRepository();
      Map<String, String> annotations = metadata.getAnnotations();
      PodTemplate podTemplate = podTemplateFromData(isName, repository, annotations);
      results.add(podTemplate);
    }
    results.addAll(extractPodTemplatesFromImageStreamTags(imageStream));
    return results;
  }

  protected static List<PodTemplate> extractPodTemplatesFromImageStreamTags(ImageStream imageStream) {
    // for slave-label, still check annotations
    // since we cannot create watches on ImageStream tags, we have to
    // traverse the tags and look for the slave label
    List<PodTemplate> results = new ArrayList<PodTemplate>();
    List<TagReference> tags = imageStream.getSpec().getTags();
    for (TagReference tagRef : tags) {
      addPodTemplateFromImageStreamTag(results, imageStream, tagRef);
    }
    return results;
  }

  protected static void addPodTemplateFromImageStreamTag(List<PodTemplate> results, ImageStream imageStream, TagReference tagRef) {
    ObjectMeta metadata = imageStream.getMetadata();
    String ns = metadata.getNamespace();
    String isName = metadata.getName();
    ImageStreamTag tag = null;
    try {
      String tagName = isName + ":" + tagRef.getName();
      tag = OpenShiftUtils.getOpenshiftClient().imageStreamTags().inNamespace(ns).withName(tagName).get();
    } catch (Throwable t) {
      LOGGER.log(FINE, "addPodTemplateFromImageStreamTag", t);
    }
    // for ImageStreamTag (IST), we can't set labels directly, but can inherit, so
    // we check annotations (if ImageStreamTag directly updated) and then labels (if
    // inherited from imagestream)
    if (tag != null) {
      ObjectMeta tagMetadata = tag.getMetadata();
      Map<String, String> tagAnnotations = tagMetadata.getAnnotations();
      String tagName = tagMetadata.getName();
      String tagImageReference = tag.getImage().getDockerImageReference();
      if (hasSlaveLabelOrAnnotation(tagAnnotations)) {
        results.add(podTemplateFromData(tagName, tagImageReference, tagAnnotations));
      } else {
        Map<String, String> tagLabels = tagMetadata.getLabels();
        if (hasSlaveLabelOrAnnotation(tagLabels)) {
          results.add(podTemplateFromData(tagName, tagImageReference, tagLabels));
        }
      }
    }
  }

  protected static PodTemplate podTemplateFromData(String name, String image, Map<String, String> map) {
    // node, pod names cannot have colons
    String templateName = name.replaceAll(":", ".");
    String label = (map != null && map.containsKey(SLAVE_LABEL)) ? map.get(SLAVE_LABEL) : name;
    return podTemplateInit(templateName, image, label);
  }

  // podTemplatesFromConfigMap takes every key from a ConfigMap and tries to
  // create a PodTemplate from the contained
  // XML.
  public static List<PodTemplate> podTemplatesFromConfigMap(ConfigMapWatcher configMapWatcher, ConfigMap configMap) {
    List<PodTemplate> results = new ArrayList<>();
    Map<String, String> data = configMap.getData();

    if (!configMapContainsSlave(configMap)) {
      return results;
    }

    XStream2 xStream2 = new XStream2();

    for (Map.Entry<String, String> entry : data.entrySet()) {
      Object podTemplate;
      try {
        podTemplate = xStream2.fromXML(entry.getValue());

        String warningPrefix = "Content of key '" + entry.getKey()
          + "' in ConfigMap '"
          + configMap.getMetadata().getName();
        if (podTemplate instanceof PodTemplate) {
          PodTemplate pt = (PodTemplate) podTemplate;

          String image = pt.getImage();
          try {
            // if requested via special prefix, convert this images
            // entry field, if not already fully qualified, as if
            // it were an IST
            // IST of form [optional_namespace]/imagestreamname:tag
            // checks based on ParseImageStreamTagName in
            // https://github.com/openshift/origin/blob/master/pkg/image/apis/image/helper.go
            if (image.startsWith(SPECIAL_IST_PREFIX)) {
              image = image.substring(SPECIAL_IST_PREFIX_IDX);
              if (image.contains("@")) {
                LOGGER.warning(warningPrefix
                  + " the presence of @ implies an image stream image, not an image stream tag, "
                  + " so no ImageStreamTag to Docker image reference translation was performed.");
              } else {
                boolean hasNamespace = hasOneAndOnlyOneWithSomethingAfter(image, "/");
                boolean hasTag = hasOneAndOnlyOneWithSomethingAfter(image, ":");
                String namespace = getAuthenticatedOpenShiftClient().getNamespace();
                String isName = image;
                String newImage = null;
                if (hasNamespace) {
                  String[] parts = image.split("/");
                  namespace = parts[0];
                  isName = parts[1];
                }
                if (hasTag) {
                  ImageStreamTag ist = getAuthenticatedOpenShiftClient()
                    .imageStreamTags()
                    .inNamespace(namespace)
                    .withName(isName).get();
                  Image imageFromIst = ist.getImage();
                  String dockerImageReference = imageFromIst.getDockerImageReference();

                  if (ist != null && imageFromIst != null && dockerImageReference != null && dockerImageReference.length() > 0) {
                    newImage = dockerImageReference;
                    LOGGER.fine(String.format("Converting image ref %s as an imagestreamtag %s to fully qualified image %s", image, isName, newImage));
                  } else {
                    LOGGER.warning(warningPrefix
                      + " used the 'imagestreamtag:' prefix in the image field, but the subsequent value, while a valid ImageStreamTag reference,"
                      + " produced no valid ImageStreaTag upon lookup,"
                      + " so no ImageStreamTag to Docker image reference translation was performed.");
                  }
                } else {
                  LOGGER.warning(warningPrefix
                    + " used the 'imagestreamtag:' prefix in the image field, but the subsequent value had no tag indicator,"
                    + " so no ImageStreamTag to Docker image reference translation was performed.");
                }
                if (newImage != null) {
                  LOGGER.fine("translated IST ref " + image + " to docker image ref " + newImage);
                  pt.getContainers().get(0).setImage(newImage);
                }
              }
            }
          } catch (Throwable t) {
            if (LOGGER.isLoggable(FINE))
              LOGGER.log(FINE, "podTemplateFromConfigMap", t);
          }
          results.add((PodTemplate) podTemplate);
        } else {
          LOGGER.warning(warningPrefix + "' is not a PodTemplate");
        }
      } catch (XStreamException xse) {
        LOGGER.warning(new IOException("Unable to read key '" + entry.getKey() + "' from ConfigMap '" + configMap.getMetadata().getName() + "'", xse).getMessage());
      } catch (Error e) {
        LOGGER.warning(new IOException("Unable to read key '" + entry.getKey() + "' from ConfigMap '" + configMap.getMetadata().getName() + "'", e).getMessage());
      }
    }

    return results;
  }

  protected static boolean configMapContainsSlave(ConfigMap configMap) {
    return hasSlaveLabelOrAnnotation(configMap.getMetadata().getLabels());
  }

  protected static boolean hasSlaveLabelOrAnnotation(Map<String, String> map) {
    if (map != null)
      return map.containsKey("role")
        && map.get("role").equals("jenkins-slave");
    return false;
  }
}

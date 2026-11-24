using UnityEngine;

namespace SkyRaid
{
    public static class SpaceBattleSceneFactory
    {
        public static void CreateGameplayObjects()
        {
            SpaceBattleConfig config = Resources.Load<SpaceBattleConfig>("SpaceBattleConfig");
            if (Camera.main == null)
            {
                GameObject cameraObject = new GameObject("Main Camera");
                cameraObject.tag = "MainCamera";
                Camera camera = cameraObject.AddComponent<Camera>();
                camera.clearFlags = CameraClearFlags.Skybox;
                camera.fieldOfView = 60f;
                camera.nearClipPlane = 0.05f;
                camera.farClipPlane = 1200f;
                camera.allowHDR = true;
                camera.transform.position = new Vector3(0f, 5f, -24f);
                camera.transform.LookAt(new Vector3(0f, 0f, 16f));
                cameraObject.AddComponent<AudioListener>();
            }

            if (UnityEngine.Object.FindObjectOfType<Light>() == null)
            {
                GameObject lightObject = new GameObject("Sunlight");
                Light sun = lightObject.AddComponent<Light>();
                sun.type = LightType.Directional;
                sun.color = new Color(1f, 0.95f, 0.87f);
                sun.intensity = 1.4f;
                lightObject.transform.rotation = Quaternion.Euler(28f, -32f, 0f);
            }

            RenderSettings.ambientMode = UnityEngine.Rendering.AmbientMode.Flat;
            RenderSettings.ambientLight = new Color(0.58f, 0.68f, 0.78f);
            RenderSettings.fog = true;
            RenderSettings.fogColor = new Color(0.67f, 0.78f, 0.86f);
            RenderSettings.fogMode = FogMode.Linear;
            RenderSettings.fogStartDistance = 115f;
            RenderSettings.fogEndDistance = 270f;
            if (config != null && config.skyboxMaterial != null)
                RenderSettings.skybox = config.skyboxMaterial;

            if (Object.FindObjectOfType<BattleDirector>() == null)
            {
                GameObject root = new GameObject("GameDirector");
                root.AddComponent<BattleDirector>();
            }
            if (Object.FindObjectOfType<PlayerShip>() == null)
            {
                GameObject player = new GameObject("Player");
                player.transform.position = new Vector3(0f, -2f, 0f);
                BoxCollider hitbox = player.AddComponent<BoxCollider>();
                hitbox.size = new Vector3(4.1f, 1.6f, 5.1f);
                hitbox.isTrigger = true;
                Rigidbody rb = player.AddComponent<Rigidbody>();
                rb.useGravity = false;
                rb.isKinematic = true;
                player.AddComponent<PlayerShip>();
                ShipAppearance app = player.AddComponent<ShipAppearance>();
                app.isEnemy = false;
                GameObject firePoint = new GameObject("FirePoint");
                firePoint.transform.SetParent(player.transform, false);
                firePoint.transform.localPosition = new Vector3(0f, 0f, 4.6f);
                app.RebuildVisual();
            }
            if (Object.FindObjectOfType<EnemySpawner>() == null)
            {
                GameObject spawner = new GameObject("EnemyPoint");
                spawner.transform.position = new Vector3(0f, 0f, 105f);
                spawner.AddComponent<EnemySpawner>();
            }
            if (Object.FindObjectOfType<BattleHUD>() == null)
            {
                GameObject hud = new GameObject("HUD");
                hud.AddComponent<BattleHUD>();
            }
        }
    }
}
